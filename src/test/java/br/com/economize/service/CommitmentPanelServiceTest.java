package br.com.economize.service;

import br.com.economize.dto.analytics.CommitmentsResponse;
import br.com.economize.dto.analytics.IncomePatternResponse;
import br.com.economize.model.IncomeSource;
import br.com.economize.model.User;
import br.com.economize.repository.IncomeSourceRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.recurrence.RecurringSeriesService;
import br.com.economize.service.wish.IncomePatternService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Parcelas e compromissos contra a renda — a pergunta do Painel de Dívidas.
 *
 * <p>Hoje é 13/11/2026; a janela padrão dos testes vai de novembro a janeiro.
 */
@ExtendWith(MockitoExtension.class)
class CommitmentPanelServiceTest {

    private static final String EMAIL = "dono@economize.test";
    private static final LocalDate HOJE = LocalDate.of(2026, 11, 13);
    private static final BigDecimal QUARENTA = new BigDecimal("40");

    @Mock
    private InstallmentProjectionService installmentProjectionService;
    @Mock
    private RecurringSeriesService recurringSeriesService;
    @Mock
    private IncomeSourceRepository incomeSourceRepository;
    @Mock
    private IncomePatternService incomePatternService;
    @Mock
    private UserRepository userRepository;

    private CommitmentPanelService service;
    private User user;
    private final List<InstallmentProjectionService.Series> series = new ArrayList<>();
    private final List<RecurringSeriesService.UpcomingDue> vencimentos = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(HOJE.atTime(12, 0).atZone(ZoneId.of("America/Sao_Paulo")).toInstant(),
                ZoneId.of("America/Sao_Paulo"));
        service = new CommitmentPanelService(installmentProjectionService, recurringSeriesService,
                incomeSourceRepository, incomePatternService, userRepository, clock, QUARENTA);
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Dono").password("x").build();
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        lenient().when(installmentProjectionService.overviewFor(EMAIL, YearMonth.of(2026, 11)))
                .thenAnswer(inv -> new InstallmentProjectionService.Overview(
                        series.size(), series.size(), BigDecimal.ZERO, series));
        lenient().when(recurringSeriesService.upcomingExpenses(eq(user.getId()), eq(HOJE), any()))
                .thenAnswer(inv -> vencimentos);
    }

    @Test
    @DisplayName("Soma parcelas e dívidas por mês e calcula o percentual da renda")
    void somaPorMes() {
        // 1/3 vista em novembro: faltam dezembro e janeiro
        parcela("Loja X - Parcela 1/3", "2027-01", 2, "100.00");
        // Última parcela em novembro, a anterior foi vista em outubro
        parcela("Mercadolivre - Parcela 2/3", "2026-11", 1, "50.00");
        divida("financiamento veiculo", "2026-11-20", "800.00");
        divida("financiamento veiculo", "2026-12-20", "800.00");
        divida("financiamento veiculo", "2027-01-20", "800.00");
        // Assinatura não é dívida
        divida("netflix", "2026-11-25", "40.00");
        renda(salario("5000.00"));

        CommitmentsResponse r = service.panel(user, HOJE, 3, QUARENTA);

        assertThat(r.income().amount()).isEqualByComparingTo("5000.00");
        assertThat(r.income().origin()).isEqualTo("SOURCES");
        assertThat(r.months()).extracting(CommitmentsResponse.CommitmentMonth::month)
                .containsExactly("2026-11", "2026-12", "2027-01");
        CommitmentsResponse.CommitmentMonth nov = r.months().get(0);
        assertThat(nov.partial()).isTrue();
        assertThat(nov.installments()).isEqualByComparingTo("50.00");
        assertThat(nov.debts()).isEqualByComparingTo("800.00");
        assertThat(nov.total()).isEqualByComparingTo("850.00");
        assertThat(nov.shareOfIncome()).isEqualByComparingTo("17.0");
        CommitmentsResponse.CommitmentMonth dez = r.months().get(1);
        assertThat(dez.partial()).isFalse();
        assertThat(dez.total()).isEqualByComparingTo("900.00");
        assertThat(dez.shareOfIncome()).isEqualByComparingTo("18.0");
        assertThat(r.peakSharePct()).isEqualByComparingTo("18.0");
        assertThat(r.aboveThreshold()).isFalse();
        verifyNoInteractions(incomePatternService);
    }

    @Test
    @DisplayName("Acima de 40% da renda o mês avisa; o limite vem na resposta")
    void avisaAcimaDoLimite() {
        parcela("Loja X - Parcela 1/3", "2027-01", 2, "100.00");
        divida("financiamento veiculo", "2026-12-20", "800.00");
        renda(salario("2000.00"));

        CommitmentsResponse r = service.panel(user, HOJE, 3, QUARENTA);

        assertThat(r.thresholdPct()).isEqualByComparingTo("40");
        assertThat(r.months().get(1).shareOfIncome()).isEqualByComparingTo("45.0");
        assertThat(r.months().get(1).aboveThreshold()).isTrue();
        assertThat(r.months().get(2).aboveThreshold()).isFalse();
        assertThat(r.aboveThreshold()).isTrue();
        assertThat(r.peakSharePct()).isEqualByComparingTo("45.0");

        // O limite é parâmetro: a 50% o mesmo mês não avisa
        assertThat(service.panel(user, HOJE, 3, new BigDecimal("50")).aboveThreshold()).isFalse();
    }

    @Test
    @DisplayName("Exatamente no limite não avisa: o aviso é para quem PASSA de 40%")
    void exatamenteNoLimite() {
        divida("emprestimo pessoal", "2026-12-05", "800.00");
        renda(salario("2000.00"));

        CommitmentsResponse r = service.panel(user, HOJE, 2, QUARENTA);

        assertThat(r.months().get(1).shareOfIncome()).isEqualByComparingTo("40.0");
        assertThat(r.aboveThreshold()).isFalse();
    }

    @Test
    @DisplayName("Sem renda confirmada não há percentual nem aviso — e vale não conta como renda")
    void semRenda() {
        divida("financiamento veiculo", "2026-12-20", "800.00");
        IncomeSource vale = fonte(IncomeSource.Kind.MEAL_VOUCHER, "900.00", true);
        IncomeSource naoConfirmada = fonte(IncomeSource.Kind.SALARY, "5000.00", false);
        renda(vale, naoConfirmada);

        CommitmentsResponse r = service.panel(user, HOJE, 3, QUARENTA);

        assertThat(r.income().amount()).isNull();
        assertThat(r.income().origin()).isEqualTo("NONE");
        assertThat(r.income().sources()).isZero();
        assertThat(r.months()).allSatisfy(m -> {
            assertThat(m.shareOfIncome()).isNull();
            assertThat(m.aboveThreshold()).isFalse();
        });
        assertThat(r.months().get(1).total()).isEqualByComparingTo("800.00");
        assertThat(r.peakSharePct()).isNull();
        assertThat(r.aboveThreshold()).isFalse();
        verifyNoInteractions(incomePatternService);
    }

    @Test
    @DisplayName("Fonte cadastrada sem valor usa o padrão medido no extrato")
    void rendaMedida() {
        IncomeSource semValor = fonte(IncomeSource.Kind.SALARY, null, true);
        renda(semValor);
        IncomePatternResponse.Pattern padrao = new IncomePatternResponse.Pattern(
                "BUSINESS_DAY_FROM_START", 5, 4, 6, 4, HOJE.minusMonths(4), HOJE.minusMonths(1),
                new BigDecimal("3000.00"), "HIGH", "por volta do 5º dia útil");
        when(incomePatternService.analyze(EMAIL)).thenReturn(new IncomePatternResponse("READY", null, HOJE,
                List.of(new IncomePatternResponse.SourcePattern(semValor.getId(), null, "SALARY", "Salário",
                        true, "MEASURED", padrao, List.of(), List.of(), null)),
                null, null, null));

        CommitmentsResponse r = service.panel(user, HOJE, 1, QUARENTA);

        assertThat(r.income().amount()).isEqualByComparingTo("3000.00");
        assertThat(r.income().origin()).isEqualTo("MEASURED");
        assertThat(r.income().sources()).isEqualTo(1);
    }

    @Test
    @DisplayName("Empréstimo com 3/12 na descrição entra uma vez, não pela projeção e pela recorrência")
    void naoContaDuasVezes() {
        parcela("Emprestimo pessoal 3/12", "2027-09", 10, "500.00");
        divida("emprestimo pessoal", "2026-12-05", "500.00");
        renda(salario("5000.00"));

        CommitmentsResponse r = service.panel(user, HOJE, 2, QUARENTA);

        CommitmentsResponse.CommitmentMonth dez = r.months().get(1);
        assertThat(dez.installments()).isEqualByComparingTo("500.00");
        assertThat(dez.debts()).isEqualByComparingTo("0.00");
        assertThat(dez.total()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("Parcela que deveria ter caído num mês passado não vira compromisso futuro")
    void mesPassadoFica() {
        // Vista em julho, faltam 3: agosto, setembro e outubro — todos passados
        parcela("Loja Y - Parcela 2/5", "2026-10", 3, "70.00");

        CommitmentsResponse r = service.panel(user, HOJE, 2, QUARENTA);

        assertThat(r.months()).allSatisfy(m -> assertThat(m.total()).isEqualByComparingTo("0.00"));
    }

    @Test
    @DisplayName("Janela e limite fora da faixa respondem erro de validação")
    void validaParametros() {
        assertThatThrownBy(() -> service.panel(EMAIL, 13, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.panel(EMAIL, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.panel(EMAIL, 6, new BigDecimal("101")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.panel(EMAIL, 6, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Sem parâmetro: seis meses a partir do mês de hoje na zona financeira")
    void padrao() {
        CommitmentsResponse r = service.panel(EMAIL, null, null);

        assertThat(r.months()).hasSize(6);
        assertThat(r.months().get(0).month()).isEqualTo("2026-11");
        assertThat(r.thresholdPct()).isEqualByComparingTo("40");
    }

    private void parcela(String descricao, String ultimoMes, int faltam, String valor) {
        BigDecimal v = new BigDecimal(valor);
        series.add(new InstallmentProjectionService.Series(descricao, 12, 1, faltam, v,
                v.multiply(BigDecimal.valueOf(faltam)), "2026-01", ultimoMes, false));
    }

    private void divida(String nome, String vencimento, String valor) {
        vencimentos.add(new RecurringSeriesService.UpcomingDue(UUID.randomUUID(), nome, null,
                LocalDate.parse(vencimento), new BigDecimal(valor), false));
    }

    private IncomeSource salario(String valor) {
        return fonte(IncomeSource.Kind.SALARY, valor, true);
    }

    private IncomeSource fonte(IncomeSource.Kind kind, String valor, boolean confirmada) {
        return IncomeSource.builder().id(UUID.randomUUID()).kind(kind).name(kind.name())
                .expectedAmount(valor != null ? new BigDecimal(valor) : null)
                .confirmed(confirmada).active(true).build();
    }

    private void renda(IncomeSource... fontes) {
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(user.getId())).thenReturn(List.of(fontes));
    }
}
