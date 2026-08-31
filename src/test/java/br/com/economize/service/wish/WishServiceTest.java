package br.com.economize.service.wish;

import br.com.economize.dto.wish.WishRequests;
import br.com.economize.dto.wish.WishResponses;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.User;
import br.com.economize.model.Wish;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.repository.WishRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WishServiceTest {

    private static final String EMAIL = "bia@economize.dev";
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private WishRepository wishRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private WishProjectionService projectionService;

    @InjectMocks
    private WishService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(USER_ID).name("Bia").email(EMAIL).password("x").build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(wishRepository.countByUserId(USER_ID)).thenReturn(0L);
        when(categoryRepository.existsById(any())).thenReturn(true);
        when(projectionService.baselineFor(USER_ID)).thenReturn(emptyBaseline());
        when(projectionService.project(any(), any())).thenReturn(emptyProjection());
    }

    @Test
    void desejoNasceComoDesejoENaoComoMeta() {
        WishResponses.WishItem item = service.create(EMAIL, new WishRequests.CreateWish(
                "Moto", new BigDecimal("18000"), null, null, null, null));

        // WISH é "eu quero"; GOAL é "estou guardando". Só a meta compete pela
        // sobra do mês, então promover sozinho seria decidir pelo usuário
        assertThat(item.status()).isEqualTo("WISH");
        assertThat(item.savedAmount()).isEqualByComparingTo("0");
    }

    @Test
    void guardadoMaiorQueOAlvoNaoPassa() {
        assertThatThrownBy(() -> service.create(EMAIL, new WishRequests.CreateWish(
                "Moto", new BigDecimal("1000"), new BigDecimal("1500"), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(wishRepository, never()).save(any());
    }

    @Test
    void categoriaInexistenteNaoPassa() {
        when(categoryRepository.existsById(any())).thenReturn(false);

        assertThatThrownBy(() -> service.create(EMAIL, new WishRequests.CreateWish(
                "Moto", new BigDecimal("18000"), null, UUID.randomUUID(), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oLimiteDeDesejosProtegeAListagem() {
        when(wishRepository.countByUserId(USER_ID)).thenReturn(100L);

        assertThatThrownBy(() -> service.create(EMAIL, new WishRequests.CreateWish(
                "Moto", new BigDecimal("18000"), null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    void aListagemCalculaORetratoFinanceiroUmaVezSo() {
        when(wishRepository.findAllByUserIdOrderByCreatedAtDesc(USER_ID))
                .thenReturn(List.of(wish("Moto", "18000"), wish("Viagem", "8000"),
                        wish("Notebook", "5000")));

        WishResponses.WishList list = service.list(EMAIL);

        assertThat(list.wishes()).hasSize(3);
        // três desejos, UMA baseline: o retrato custa seis consultas de ciclo e
        // repeti-lo por item multiplicaria isso pela lista inteira
        verify(projectionService, times(1)).baselineFor(USER_ID);
        verify(projectionService, times(3)).project(any(), any());
    }

    @Test
    void patchAlteraSoOQueVeio() {
        UUID id = UUID.randomUUID();
        Wish existente = wish("Moto", "18000");
        existente.setNote("guardar no fim do mes");
        when(wishRepository.findByIdAndUserId(id, USER_ID)).thenReturn(Optional.of(existente));

        service.update(EMAIL, id, new WishRequests.UpdateWish(
                "Moto 300cc", null, null, null, null, null, null));

        assertThat(existente.getName()).isEqualTo("Moto 300cc");
        assertThat(existente.getTargetAmount()).isEqualByComparingTo("18000");
        assertThat(existente.getNote()).isEqualTo("guardar no fim do mes");
    }

    @Test
    void subirAlvoEGuardadoJuntosNaoEBloqueado() {
        UUID id = UUID.randomUUID();
        Wish existente = wish("Moto", "1000");
        existente.setSavedAmount(new BigDecimal("900"));
        when(wishRepository.findByIdAndUserId(id, USER_ID)).thenReturn(Optional.of(existente));

        // comparar campo a campo rejeitaria: 2.000 guardados contra o alvo
        // ANTIGO de 1.000. A checagem tem de ver o estado final
        service.update(EMAIL, id, new WishRequests.UpdateWish(
                null, new BigDecimal("5000"), new BigDecimal("2000"), null, null, null, null));

        assertThat(existente.getTargetAmount()).isEqualByComparingTo("5000");
        assertThat(existente.getSavedAmount()).isEqualByComparingTo("2000");
    }

    @Test
    void patchQueDeixaOGuardadoAcimaDoAlvoEBloqueado() {
        UUID id = UUID.randomUUID();
        Wish existente = wish("Moto", "1000");
        when(wishRepository.findByIdAndUserId(id, USER_ID)).thenReturn(Optional.of(existente));

        assertThatThrownBy(() -> service.update(EMAIL, id, new WishRequests.UpdateWish(
                null, null, new BigDecimal("4000"), null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void desejoViraMetaPeloStatus() {
        UUID id = UUID.randomUUID();
        Wish existente = wish("Moto", "18000");
        when(wishRepository.findByIdAndUserId(id, USER_ID)).thenReturn(Optional.of(existente));

        WishResponses.WishItem item = service.update(EMAIL, id, new WishRequests.UpdateWish(
                null, null, null, null, null, null, "goal"));

        assertThat(item.status()).isEqualTo("GOAL");
    }

    @Test
    void statusInvalidoDizQuaisSaoOsValidos() {
        UUID id = UUID.randomUUID();
        when(wishRepository.findByIdAndUserId(id, USER_ID)).thenReturn(Optional.of(wish("Moto", "18000")));

        assertThatThrownBy(() -> service.update(EMAIL, id, new WishRequests.UpdateWish(
                null, null, null, null, null, null, "COMPRADO")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PURCHASED");
    }

    @Test
    void aCompraPreservaOQuantoJaHaviaSidoGuardado() {
        UUID id = UUID.randomUUID();
        Wish existente = wish("Moto", "18000");
        existente.setSavedAmount(new BigDecimal("12000"));
        when(wishRepository.findByIdAndUserId(id, USER_ID)).thenReturn(Optional.of(existente));

        UUID transacao = UUID.randomUUID();
        WishResponses.WishItem item = service.purchase(EMAIL, id,
                new WishRequests.PurchaseWish(LocalDate.of(2026, 8, 20), transacao));

        assertThat(item.status()).isEqualTo("PURCHASED");
        assertThat(item.purchasedAt()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(item.purchaseTransactionId()).isEqualTo(transacao);
        // o histórico deve continuar contando quanto havia juntado na compra
        assertThat(item.savedAmount()).isEqualByComparingTo("12000");
    }

    @Test
    void compraSemDataUsaHoje() {
        UUID id = UUID.randomUUID();
        when(wishRepository.findByIdAndUserId(id, USER_ID)).thenReturn(Optional.of(wish("Moto", "18000")));

        WishResponses.WishItem item = service.purchase(EMAIL, id,
                new WishRequests.PurchaseWish(null, null));

        assertThat(item.purchasedAt()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
    }

    @Test
    void compraNoFuturoNaoPassa() {
        UUID id = UUID.randomUUID();
        when(wishRepository.findByIdAndUserId(id, USER_ID)).thenReturn(Optional.of(wish("Moto", "18000")));

        assertThatThrownBy(() -> service.purchase(EMAIL, id, new WishRequests.PurchaseWish(
                LocalDate.now(ZoneOffset.UTC).plusDays(1), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void desejoDeOutroDonoNaoEEncontrado() {
        UUID alheio = UUID.randomUUID();
        when(wishRepository.findByIdAndUserId(alheio, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(EMAIL, alheio)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(EMAIL, alheio)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(EMAIL, alheio, new WishRequests.UpdateWish(
                null, null, null, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void observacaoVaziaViraNuloEmVezDeStringEmBranco() {
        WishResponses.WishItem item = service.create(EMAIL, new WishRequests.CreateWish(
                "Moto", new BigDecimal("18000"), null, null, null, "   "));

        assertThat(item.note()).isNull();
    }

    private static Wish wish(String name, String target) {
        return Wish.builder()
                .id(UUID.randomUUID())
                .name(name)
                .targetAmount(new BigDecimal(target))
                .savedAmount(BigDecimal.ZERO)
                .status(Wish.Status.WISH)
                .build();
    }

    private static WishBaseline emptyBaseline() {
        return new WishBaseline(BigDecimal.ZERO, null, null, null, null, null, 0,
                List.of(WishBaseline.GAP_WORK_PROFILE));
    }

    private static WishProjection emptyProjection() {
        return new WishProjection(BigDecimal.ZERO, null, null, null, null, null, null, false, List.of());
    }
}
