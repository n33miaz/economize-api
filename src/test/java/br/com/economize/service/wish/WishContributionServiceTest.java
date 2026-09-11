package br.com.economize.service.wish;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.User;
import br.com.economize.model.Wish;
import br.com.economize.model.WishContribution;
import br.com.economize.repository.UserRepository;
import br.com.economize.repository.WishContributionRepository;
import br.com.economize.repository.WishRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EC-205 — o progresso de uma meta passa a ter rastro.
 *
 * <p>O que esta suíte trava é a regra que o recurso existe para impor: o saldo
 * da meta nunca muda sem uma linha explicando por quê, e a sobra de um ciclo
 * entra uma vez só.
 */
@ExtendWith(MockitoExtension.class)
class WishContributionServiceTest {

    private static final String EMAIL = "dono@economize.test";

    @Mock private WishContributionRepository contributionRepository;
    @Mock private WishRepository wishRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private WishContributionService service;

    private User user;
    private Wish wish;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).build();
        wish = Wish.builder()
                .id(UUID.randomUUID())
                .user(user)
                .name("Moto")
                .targetAmount(new BigDecimal("18000.00"))
                .savedAmount(new BigDecimal("1200.00"))
                .status(Wish.Status.GOAL)
                .build();
    }

    private void comUsuarioEMeta() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(wishRepository.findByIdAndUserId(wish.getId(), user.getId()))
                .thenReturn(Optional.of(wish));
    }

    private void gravaOQueChega() {
        when(contributionRepository.save(any(WishContribution.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Aporte sobe o saldo da meta e grava a linha que o explica")
    void aporteSobeSaldoEGravaLinha() {
        comUsuarioEMeta();
        gravaOQueChega();

        WishContributionService.Result r =
                service.contribute(EMAIL, wish.getId(), new BigDecimal("300.00"), null, null);

        assertThat(r.savedAmount()).isEqualByComparingTo("1500.00");
        assertThat(wish.getSavedAmount()).isEqualByComparingTo("1500.00");
        verify(contributionRepository).save(any(WishContribution.class));
        verify(wishRepository).save(wish);
    }

    @Test
    @DisplayName("Com ciclo, o aporte é MEDIDO; sem ciclo, DECLARADO")
    void origemSeparaMedidoDeDeclarado() {
        comUsuarioEMeta();
        gravaOQueChega();

        assertThat(service.contribute(EMAIL, wish.getId(), new BigDecimal("100"), "2026-09", null)
                .contribution().origin()).isEqualTo(WishContribution.Origin.MEASURED);

        assertThat(service.contribute(EMAIL, wish.getId(), new BigDecimal("100"), null, null)
                .contribution().origin()).isEqualTo(WishContribution.Origin.DECLARED);
    }

    @Test
    @DisplayName("A sobra do mesmo ciclo não entra duas vezes")
    void sobraDoCicloEntraUmaVezSo() {
        comUsuarioEMeta();
        when(contributionRepository.findByWishIdAndCycleMonth(wish.getId(), "2026-09"))
                .thenReturn(Optional.of(WishContribution.builder()
                        .amount(new BigDecimal("420.00")).cycleMonth("2026-09").build()));

        assertThatThrownBy(() ->
                service.contribute(EMAIL, wish.getId(), new BigDecimal("420.00"), "2026-09", null))
                .isInstanceOf(IllegalArgumentException.class)
                // A frase diz que JÁ foi guardada — "deu erro" faria a pessoa
                // achar que o dinheiro não entrou e tentar de novo
                .hasMessageContaining("já foi guardada");

        // E o saldo fica exatamente como estava
        assertThat(wish.getSavedAmount()).isEqualByComparingTo("1200.00");
        verify(contributionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Valor negativo devolve: sai do saldo e FICA no histórico")
    void negativoDevolveESobraNoHistorico() {
        comUsuarioEMeta();
        gravaOQueChega();

        WishContributionService.Result r =
                service.contribute(EMAIL, wish.getId(), new BigDecimal("-200.00"), null, "errei o dedo");

        assertThat(r.savedAmount()).isEqualByComparingTo("1000.00");
        ArgumentCaptor<WishContribution> captor = ArgumentCaptor.forClass(WishContribution.class);
        verify(contributionRepository).save(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("-200.00");
        assertThat(captor.getValue().getNote()).isEqualTo("errei o dedo");
    }

    @Test
    @DisplayName("Não dá para devolver mais do que está guardado")
    void naoDevolveMaisDoQueTem() {
        comUsuarioEMeta();

        assertThatThrownBy(() ->
                service.contribute(EMAIL, wish.getId(), new BigDecimal("-5000.00"), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mais do que está guardado");

        assertThat(wish.getSavedAmount()).isEqualByComparingTo("1200.00");
    }

    @Test
    @DisplayName("Zero não é aporte, e a recusa aponta o caminho da correção")
    void zeroNaoEAporte() {
        comUsuarioEMeta();

        assertThatThrownBy(() -> service.contribute(EMAIL, wish.getId(), BigDecimal.ZERO, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negativo");
    }

    @Test
    @DisplayName("Meta já comprada não recebe aporte")
    void metaEncerradaNaoRecebe() {
        wish.setStatus(Wish.Status.PURCHASED);
        comUsuarioEMeta();

        assertThatThrownBy(() ->
                service.contribute(EMAIL, wish.getId(), new BigDecimal("100"), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encerrada");
    }

    @Test
    @DisplayName("Ciclo torto responde com o formato esperado, e não grava nada")
    void cicloTortoRecusa() {
        comUsuarioEMeta();

        assertThatThrownBy(() ->
                service.contribute(EMAIL, wish.getId(), new BigDecimal("100"), "setembro", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM");

        verify(contributionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Meta de outra pessoa responde 'não encontrada', e não 403")
    void metaDeOutroDono() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        UUID alheia = UUID.randomUUID();
        when(wishRepository.findByIdAndUserId(alheia, user.getId())).thenReturn(Optional.empty());

        // 403 confirmaria que aquele id existe
        assertThatThrownBy(() ->
                service.contribute(EMAIL, alheia, new BigDecimal("100"), null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("O extrato sai do mais novo para o mais velho")
    void extratoDoMaisNovoParaOMaisVelho() {
        comUsuarioEMeta();
        when(contributionRepository.findAllByWishIdOrderByCreatedAtDesc(wish.getId()))
                .thenReturn(List.of(
                        WishContribution.builder().id(UUID.randomUUID())
                                .amount(new BigDecimal("300")).origin(WishContribution.Origin.MEASURED)
                                .cycleMonth("2026-09").build(),
                        WishContribution.builder().id(UUID.randomUUID())
                                .amount(new BigDecimal("900")).origin(WishContribution.Origin.DECLARED)
                                .build()));

        List<WishContributionService.Item> extrato = service.historyFor(EMAIL, wish.getId());

        assertThat(extrato).hasSize(2);
        assertThat(extrato.get(0).cycleMonth()).isEqualTo("2026-09");
        assertThat(extrato.get(1).origin()).isEqualTo(WishContribution.Origin.DECLARED);
    }

    @Test
    @DisplayName("Meta sem saldo gravado parte de zero, e não estoura")
    void saldoNuloParteDeZero() {
        wish.setSavedAmount(null);
        comUsuarioEMeta();
        gravaOQueChega();

        assertThat(service.contribute(EMAIL, wish.getId(), new BigDecimal("50.00"), null, null)
                .savedAmount()).isEqualByComparingTo("50.00");
    }
}
