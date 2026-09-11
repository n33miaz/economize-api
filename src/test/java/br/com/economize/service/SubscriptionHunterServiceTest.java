package br.com.economize.service;

import br.com.economize.model.Category;
import br.com.economize.model.RecurringSeries;
import br.com.economize.model.User;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.RecurringSeriesRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * EC-203 — o caça-assinaturas, e o filtro que o faz valer alguma coisa.
 *
 * <p>A regra ingênua ("mesmo valor em três meses ou mais") foi medida contra o
 * extrato real do dono e achou <b>24 candidatas</b>, das quais só <b>duas</b>
 * são assinaturas: Inter Cel (R$ 30 em 16 meses) e Spotify (R$ 23,90). As 22
 * restantes estão reproduzidas aqui, uma a uma, como recusas.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionHunterServiceTest {

    private static final String EMAIL = "dono@economize.test";
    private static final OffsetDateTime AGORA = OffsetDateTime.parse("2026-09-10T12:00:00Z");

    @Mock
    private RecurringSeriesRepository recurringSeriesRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SubscriptionHunterService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Dono").password("x").build();
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        lenient().when(categoryRepository.findVisibleTo(user.getId())).thenReturn(List.of());
    }

    @Test
    @DisplayName("As duas assinaturas reais do dono, com o número que faz cancelar")
    void asDuasReais() {
        // Ninguém cancela uma de R$ 23,90. Muita gente cancela uma de R$ 286,80
        daSeries(
                assinatura("Inter Cel", "30.00", 16, AGORA.minusDays(5)),
                assinatura("Spotify", "23.90", 4, AGORA.minusDays(10)));

        SubscriptionHunterService.Report relatorio = service.huntFor(EMAIL, AGORA);

        assertThat(relatorio.subscriptions()).isEqualTo(2);
        assertThat(relatorio.yearlyTotal()).isEqualByComparingTo(new BigDecimal("646.80"));
        // Da mais cara por ano para a mais barata: é a ordem da decisão
        assertThat(relatorio.details()).extracting(
                        SubscriptionHunterService.Subscription::name)
                .containsExactly("Inter Cel", "Spotify");
        assertThat(relatorio.details().get(1).yearlyAmount())
                .isEqualByComparingTo(new BigDecimal("286.80"));
    }

    @Test
    @DisplayName("As 22 falsas candidatas do extrato real são todas recusadas")
    void asVinteEDuasFalsas() {
        // Aplicação de CDB de R$ 1,20 — abaixo do piso E dinheiro trocando de gaveta
        assertThat(SubscriptionHunterService.ehAssinatura(
                assinatura("Aplicação Cdb Porquinho", "1.20", 8, AGORA))).isFalse();
        // Saque de R$ 20/40/50 — valor redondo se repete por ser redondo
        // (chega aqui como série IRREGULAR, que é o que ele é)
        assertThat(SubscriptionHunterService.ehAssinatura(
                comCadencia(assinatura("SAQUE BANCO 24H", "20.00", 7, AGORA),
                        RecurringSeries.Cadence.IRREGULAR))).isFalse();
        // Mesada de R$ 30 para a Alice: recorrente, e não é assinatura —
        // a série é de transferência e não tem valor FIXO de cobrança
        assertThat(SubscriptionHunterService.ehAssinatura(
                comValor(assinatura("Pix Alice dos Santos", "30.00", 3, AGORA),
                        RecurringSeries.AmountType.VARIABLE))).isFalse();
        // Passagem de R$ 4 e mercadinho de R$ 6 — coincidência de preço baixo
        assertThat(SubscriptionHunterService.ehAssinatura(
                assinatura("Mp Bilhetemiquei", "4.00", 3, AGORA))).isFalse();
        assertThat(SubscriptionHunterService.ehAssinatura(
                assinatura("Mercadinho e Adega", "4.99", 3, AGORA))).isFalse();
    }

    @Test
    @DisplayName("O piso é R$ 5,00 mensais, e a borda é inclusiva")
    void oPiso() {
        assertThat(SubscriptionHunterService.ehAssinatura(
                assinatura("Algo", "5.00", 3, AGORA))).isTrue();
        assertThat(SubscriptionHunterService.ehAssinatura(
                assinatura("Algo", "4.99", 3, AGORA))).isFalse();
    }

    @Test
    @DisplayName("Trimestral divide por TRÊS, não por doze")
    void trimestral() {
        // R$ 90 a cada três meses custa R$ 30 por mês e R$ 360 por ano.
        // Dividir por doze diria R$ 7,50 e faria a mais cara parecer a mais barata
        daSeries(comCadencia(assinatura("Plano trimestral", "90.00", 4, AGORA.minusDays(5)),
                RecurringSeries.Cadence.QUARTERLY));

        SubscriptionHunterService.Report relatorio = service.huntFor(EMAIL, AGORA);

        assertThat(relatorio.details()).singleElement().satisfies(assinatura -> {
            assertThat(assinatura.monthlyAmount()).isEqualByComparingTo(new BigDecimal("30.00"));
            assertThat(assinatura.yearlyAmount()).isEqualByComparingTo(new BigDecimal("360.00"));
        });
    }

    @Test
    @DisplayName("Assinatura parada há mais de 45 dias vira pergunta")
    void assinaturaParada() {
        // Ou foi cancelada e o app não sabe, ou vai voltar a cobrar de
        // surpresa. Nos dois casos a pessoa quer saber
        daSeries(assinatura("Streaming esquecido", "19.90", 12, AGORA.minusDays(60)));

        SubscriptionHunterService.Report relatorio = service.huntFor(EMAIL, AGORA);

        assertThat(relatorio.silentCount()).isEqualTo(1);
        assertThat(relatorio.details()).singleElement()
                .extracting(SubscriptionHunterService.Subscription::silent)
                .isEqualTo(true);
    }

    @Test
    @DisplayName("Mensal atrasada em quinze dias é atraso de fatura, não abandono")
    void atrasoNaoEAbandono() {
        daSeries(assinatura("Streaming", "19.90", 12, AGORA.minusDays(40)));

        assertThat(service.huntFor(EMAIL, AGORA).silentCount()).isZero();
    }

    @Test
    @DisplayName("Receita, descartada e inativa não entram")
    void oQueNuncaEntra() {
        RecurringSeries receita = assinatura("Salário", "5000.00", 12, AGORA);
        receita.setFlow(RecurringSeries.Flow.INCOME);
        RecurringSeries descartada = assinatura("Algo", "30.00", 5, AGORA);
        descartada.setDismissed(true);
        RecurringSeries inativa = assinatura("Algo", "30.00", 5, AGORA);
        inativa.setActive(false);
        daSeries(receita, descartada, inativa);

        SubscriptionHunterService.Report relatorio = service.huntFor(EMAIL, AGORA);

        assertThat(relatorio.subscriptions()).isZero();
        // o denominador continua contando o que foi olhado
        assertThat(relatorio.seriesExamined()).isEqualTo(3);
    }

    @Test
    @DisplayName("Vista uma vez só não é recorrência provada")
    void umaVezSoNaoConta() {
        assertThat(SubscriptionHunterService.ehAssinatura(
                assinatura("Compra única", "99.00", 1, AGORA))).isFalse();
    }

    @Test
    @DisplayName("A categoria acompanha quando existe, e nulo não quebra")
    void categoriaOpcional() {
        UUID catId = UUID.randomUUID();
        RecurringSeries comCategoria = assinatura("Spotify", "23.90", 4, AGORA);
        comCategoria.setCategoryId(catId);
        when(categoryRepository.findVisibleTo(user.getId())).thenReturn(
                List.of(Category.builder().id(catId).name("Streaming").build()));
        daSeries(comCategoria, assinatura("Sem categoria", "30.00", 4, AGORA));

        SubscriptionHunterService.Report relatorio = service.huntFor(EMAIL, AGORA);

        assertThat(relatorio.details()).extracting(
                        SubscriptionHunterService.Subscription::category)
                .containsExactlyInAnyOrder("Streaming", null);
    }

    @Test
    @DisplayName("Sem série nenhuma, o relatório é zero e não estoura")
    void semSeries() {
        daSeries();

        SubscriptionHunterService.Report relatorio = service.huntFor(EMAIL, AGORA);

        assertThat(relatorio.subscriptions()).isZero();
        assertThat(relatorio.yearlyTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------ apoio

    private void daSeries(RecurringSeries... series) {
        when(recurringSeriesRepository.findAllByUserId(user.getId())).thenReturn(List.of(series));
    }

    private RecurringSeries assinatura(String nome, String valor, int ocorrencias,
                                       OffsetDateTime vistaEm) {
        return RecurringSeries.builder()
                .id(UUID.randomUUID())
                .user(user)
                .merchantKey(nome.toLowerCase())
                .displayName(nome)
                .flow(RecurringSeries.Flow.EXPENSE)
                .cadence(RecurringSeries.Cadence.MONTHLY)
                .amountType(RecurringSeries.AmountType.FIXED)
                .expectedAmount(new BigDecimal(valor))
                .occurrences(ocorrencias)
                .firstSeenAt(vistaEm.minusMonths(ocorrencias))
                .lastSeenAt(vistaEm)
                .active(true)
                .dismissed(false)
                .source(RecurringSeries.Source.DETECTED)
                .build();
    }

    private RecurringSeries comCadencia(RecurringSeries serie, RecurringSeries.Cadence cadencia) {
        serie.setCadence(cadencia);
        return serie;
    }

    private RecurringSeries comValor(RecurringSeries serie, RecurringSeries.AmountType tipo) {
        serie.setAmountType(tipo);
        return serie;
    }
}
