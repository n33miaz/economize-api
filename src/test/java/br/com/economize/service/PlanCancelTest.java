package br.com.economize.service;

import br.com.economize.config.PlanProperties;
import br.com.economize.model.Plan;
import br.com.economize.model.User;
import br.com.economize.repository.PlanInterestRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EC-208 — cancelar em um toque, com a data em que a cobrança para.
 *
 * <p>O que esta suíte trava é a regra que separa um cancelamento honesto de um
 * golpe: <b>o acesso pago já comprado não é cortado</b>.
 */
@ExtendWith(MockitoExtension.class)
class PlanCancelTest {

    private static final String EMAIL = "dono@economize.test";

    @Mock private UserRepository userRepository;
    @Mock private PlanInterestRepository interestRepository;
    @Mock private PlanProperties properties;

    @InjectMocks private PlanService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(UUID.randomUUID())
                .email(EMAIL)
                .plan(Plan.PLUS)
                .planUntil(OffsetDateTime.now().plusDays(12))
                .build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("Cancelar marca a data do pedido e NÃO corta o acesso comprado")
    void cancelaSemCortarAcesso() {
        OffsetDateTime valeAte = user.getPlanUntil();

        PlanService.CancelOutcome r = service.cancel(EMAIL);

        assertThat(r.cancelledAt()).isNotNull();
        assertThat(r.activeUntil()).isEqualTo(valeAte);
        // Cortar aqui seria ficar com o dinheiro e tirar o serviço
        assertThat(user.isPlus()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("A resposta traz a DATA em que a cobrança para")
    void respostaTrazADataExata() {
        PlanService.CancelOutcome r = service.cancel(EMAIL);

        assertThat(r.message())
                .contains("Nenhuma cobrança nova")
                .contains(user.getPlanUntil().toLocalDate().toString());
    }

    @Test
    @DisplayName("Cancelar duas vezes devolve a mesma resposta, sem erro")
    void cancelarDeNovoNaoFalha() {
        PlanService.CancelOutcome primeiro = service.cancel(EMAIL);
        PlanService.CancelOutcome segundo = service.cancel(EMAIL);

        // Quem toca duas vezes está inseguro; um erro na segunda confirmaria
        // exatamente o medo que motivou o segundo toque
        assertThat(segundo.cancelledAt()).isEqualTo(primeiro.cancelledAt());
        verify(userRepository, times(1)).save(user);
    }

    @Test
    @DisplayName("Plus sem prazo: a frase não inventa cobrança nem data")
    void semPrazoNaoInventaData() {
        user.setPlanUntil(null);

        PlanService.CancelOutcome r = service.cancel(EMAIL);

        assertThat(r.activeUntil()).isNull();
        assertThat(r.message())
                .contains("não tinha cobrança associada")
                .doesNotContain("até");
    }

    @Test
    @DisplayName("Conta gratuita não tem o que cancelar, e a recusa diz isso")
    void contaGratuitaRecusa() {
        user.setPlan(Plan.FREE);
        user.setPlanUntil(null);

        assertThatThrownBy(() -> service.cancel(EMAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("já está no plano gratuito");
    }

    @Test
    @DisplayName("Plus vencido conta como gratuito — não há renovação para cancelar")
    void plusVencidoContaComoGratuito() {
        user.setPlanUntil(OffsetDateTime.now().minusDays(1));

        assertThatThrownBy(() -> service.cancel(EMAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plano gratuito");
    }
}
