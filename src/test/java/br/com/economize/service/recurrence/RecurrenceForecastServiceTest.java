package br.com.economize.service.recurrence;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.recurrence.ForecastItemResponse;
import br.com.economize.dto.recurrence.ForecastMonthResponse;
import br.com.economize.dto.recurrence.ForecastResponse;
import br.com.economize.model.RecurringSeries;
import br.com.economize.model.User;
import br.com.economize.repository.RecurringSeriesLinkRepository;
import br.com.economize.repository.RecurringSeriesRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Fixtures sintéticas; a data "hoje" é fixa (2026-08-15) para o teste ser determinístico. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecurrenceForecastServiceTest {

    private static final String EMAIL = "bia@economize.dev";
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);

    @Mock
    private RecurringSeriesRepository seriesRepository;

    @Mock
    private RecurringSeriesLinkRepository linkRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private RecurrenceForecastService service;

    private final User user = User.builder()
            .id(UUID.randomUUID()).name("Bia").email(EMAIL).password("x").build();

    @BeforeEach
    void setUp() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    @Test
    void currentMonthProjectsOnlyWhatIsStillExpected() {
        RecurringSeries rent = monthly("aluguel", "Aluguel", RecurringSeries.Flow.EXPENSE,
                5, "1500.00", RecurringSeries.Source.USER);
        RecurringSeries salary = monthly("salario", "Salário", RecurringSeries.Flow.INCOME,
                30, "4500.00", RecurringSeries.Source.DETECTED);
        stubSeries(rent, salary);
        // o aluguel já caiu neste mês: vira settled e sai da soma
        when(linkRepository.countBySeriesIdInPeriod(anyList(), any(), any()))
                .thenReturn(List.of(occurrences(rent.getId(), 1)));

        ForecastResponse response = service.forecast(EMAIL, 2, null, null, TODAY);

        ForecastMonthResponse current = response.months().get(0);
        assertThat(current.month()).isEqualTo("2026-08");
        assertThat(current.expectedIncome()).isEqualByComparingTo("4500.00");
        assertThat(current.expectedExpense()).isEqualByComparingTo("0.00");
        assertThat(current.expectedNet()).isEqualByComparingTo("4500.00");
        assertThat(current.cumulativeNet()).isEqualByComparingTo("4500.00");
        // o item liquidado segue visível, com o valor de referência
        ForecastItemResponse settled = current.items().stream()
                .filter(ForecastItemResponse::settled).findFirst().orElseThrow();
        assertThat(settled.displayName()).isEqualTo("Aluguel");
        assertThat(settled.amount()).isEqualByComparingTo("1500.00");
        assertThat(settled.dueDay()).isEqualTo(5);

        // no mês seguinte a série volta a contar normalmente
        ForecastMonthResponse next = response.months().get(1);
        assertThat(next.month()).isEqualTo("2026-09");
        assertThat(next.expectedExpense()).isEqualByComparingTo("1500.00");
        assertThat(next.expectedNet()).isEqualByComparingTo("3000.00");
        assertThat(next.cumulativeNet()).isEqualByComparingTo("7500.00");
        assertThat(next.items()).noneMatch(ForecastItemResponse::settled);
    }

    @Test
    void endsAtStopsProjectionIncludingAnchorAfterTheEndDay() {
        RecurringSeries installment = monthly("cursox", "Curso X", RecurringSeries.Flow.EXPENSE,
                20, "300.00", RecurringSeries.Source.USER);
        installment.setStartsAt(LocalDate.of(2026, 5, 20));
        installment.setEndsAt(LocalDate.of(2026, 9, 10));
        stubSeries(installment);

        ForecastResponse response = service.forecast(EMAIL, 3, null, null, TODAY);

        // agosto projeta (dia 20 <= fim); setembro NÃO (dia 20 > 10/09); outubro NÃO
        assertThat(response.months().get(0).items()).hasSize(1);
        assertThat(response.months().get(1).items()).isEmpty();
        assertThat(response.months().get(2).items()).isEmpty();
        assertThat(response.months().get(1).expectedExpense()).isEqualByComparingTo("0.00");
    }

    @Test
    void futureStartsAtOnlyProjectsFromItsOwnWindow() {
        RecurringSeries newRent = monthly("chacara", "Chácara", RecurringSeries.Flow.EXPENSE,
                5, "900.00", RecurringSeries.Source.USER);
        // começa em 10/09 com âncora dia 5: a 1ª cobrança só cabe em outubro
        newRent.setStartsAt(LocalDate.of(2026, 9, 10));
        stubSeries(newRent);

        ForecastResponse response = service.forecast(EMAIL, 3, null, null, TODAY);

        assertThat(response.months().get(0).items()).isEmpty(); // agosto
        assertThat(response.months().get(1).items()).isEmpty(); // setembro (dia 5 < 10/09)
        assertThat(response.months().get(2).items()).hasSize(1); // outubro
        assertThat(response.months().get(2).expectedExpense()).isEqualByComparingTo("900.00");
    }

    @Test
    void weeklyProjectsTheMonthlyEquivalentAndSettlesGradually() {
        RecurringSeries groceries = weekly("feira", "Feira", "100.00", day(2026, 8, 8));
        stubSeries(groceries);
        // duas feiras já caíram no mês: o restante esperado abate 2 × 100
        when(linkRepository.countBySeriesIdInPeriod(anyList(), any(), any()))
                .thenReturn(List.of(occurrences(groceries.getId(), 2)));

        ForecastResponse response = service.forecast(EMAIL, 2, null, null, TODAY);

        // 100 × 52 ÷ 12 = 433.33 (HALF_UP, 2 casas)
        ForecastItemResponse current = response.months().get(0).items().get(0);
        assertThat(current.settled()).isFalse();
        assertThat(current.dueDay()).isNull();
        assertThat(current.amount()).isEqualByComparingTo("233.33");
        assertThat(response.months().get(0).expectedExpense()).isEqualByComparingTo("233.33");

        ForecastItemResponse next = response.months().get(1).items().get(0);
        assertThat(next.amount()).isEqualByComparingTo("433.33");
    }

    @Test
    void weeklyFullySettledMonthStopsCounting() {
        RecurringSeries groceries = weekly("feira", "Feira", "100.00", day(2026, 8, 14));
        stubSeries(groceries);
        when(linkRepository.countBySeriesIdInPeriod(anyList(), any(), any()))
                .thenReturn(List.of(occurrences(groceries.getId(), 5)));

        ForecastResponse response = service.forecast(EMAIL, 1, null, null, TODAY);

        ForecastItemResponse item = response.months().get(0).items().get(0);
        assertThat(item.settled()).isTrue();
        assertThat(response.months().get(0).expectedExpense()).isEqualByComparingTo("0.00");
    }

    @Test
    void internalIrregularInactiveAndDismissedNeverProject() {
        RecurringSeries internal = monthly("pereira", "Entre bancos", RecurringSeries.Flow.INTERNAL,
                1, "500.00", RecurringSeries.Source.DETECTED);
        RecurringSeries irregular = monthly("cantina", "Cantina", RecurringSeries.Flow.EXPENSE,
                null, "50.00", RecurringSeries.Source.DETECTED);
        irregular.setCadence(RecurringSeries.Cadence.IRREGULAR);
        RecurringSeries inactive = monthly("fitmax", "Academia", RecurringSeries.Flow.EXPENSE,
                10, "99.90", RecurringSeries.Source.DETECTED);
        inactive.setActive(false);
        RecurringSeries dismissed = monthly("clube", "Clube", RecurringSeries.Flow.EXPENSE,
                10, "80.00", RecurringSeries.Source.DETECTED);
        dismissed.setDismissed(true);
        stubSeries(internal, irregular, inactive, dismissed);

        ForecastResponse response = service.forecast(EMAIL, 1, null, null, TODAY);

        assertThat(response.months().get(0).items()).isEmpty();
        assertThat(response.months().get(0).expectedNet()).isEqualByComparingTo("0.00");
    }

    @Test
    void quarterlyOnlyLandsOnItsCycleMonths() {
        RecurringSeries quarterly = monthly("zetacel", "Plano Zetacel", RecurringSeries.Flow.EXPENSE,
                9, "69.90", RecurringSeries.Source.DETECTED);
        quarterly.setCadence(RecurringSeries.Cadence.QUARTERLY);
        quarterly.setLastSeenAt(day(2026, 6, 9));
        stubSeries(quarterly);

        ForecastResponse response = service.forecast(EMAIL, 3, null, null, TODAY);

        assertThat(response.months().get(0).items()).isEmpty(); // agosto
        assertThat(response.months().get(1).items()).hasSize(1); // setembro (jun + 3)
        assertThat(response.months().get(1).items().get(0).dueDay()).isEqualTo(9);
        assertThat(response.months().get(2).items()).isEmpty(); // outubro
    }

    @Test
    void chargeThatSlidBeforeTheAnchorIsNotProjectedAgainInItsOwnMonth() {
        // âncora dia 2, cobrança de fevereiro caiu adiantada em 30/01: contar
        // "todo mês tem a âncora" somava a mesma cobrança duas vezes — uma como
        // ocorrência real de janeiro, outra como projeção de fevereiro
        RecurringSeries subscription = monthly("melodia", "Streaming Melodia",
                RecurringSeries.Flow.EXPENSE, 2, "21.90", RecurringSeries.Source.DETECTED);
        subscription.setLastSeenAt(day(2026, 1, 30));
        stubSeries(subscription);
        when(linkRepository.countBySeriesIdInPeriod(anyList(), any(), any()))
                .thenReturn(List.of(occurrences(subscription.getId(), 1)));

        ForecastResponse response = service.forecast(EMAIL, 3, null, null, LocalDate.of(2026, 1, 31));

        // a listagem já dizia 02/03 — a previsão passa a concordar com ela
        assertThat(RecurringSeriesService.nextDueDate(subscription)).isEqualTo(LocalDate.of(2026, 3, 2));
        ForecastMonthResponse january = response.months().get(0);
        assertThat(january.items()).singleElement()
                .extracting(ForecastItemResponse::settled).isEqualTo(true);
        assertThat(january.expectedExpense()).isEqualByComparingTo("0.00");
        ForecastMonthResponse february = response.months().get(1);
        assertThat(february.items()).isEmpty();
        assertThat(february.expectedExpense()).isEqualByComparingTo("0.00");
        ForecastMonthResponse march = response.months().get(2);
        assertThat(march.items()).singleElement()
                .extracting(ForecastItemResponse::dueDay).isEqualTo(2);
        assertThat(march.expectedExpense()).isEqualByComparingTo("21.90");
    }

    @Test
    void quarterlyKeepsThePhaseOfTheListingNextDueDate() {
        // cobrança trimestral de âncora 2 que caiu em 30/06: o trimestre seguinte
        // é 02/10, não "junho + 3 meses" do calendário
        RecurringSeries plan = monthly("zetacel", "Plano Zetacel", RecurringSeries.Flow.EXPENSE,
                2, "69.90", RecurringSeries.Source.DETECTED);
        plan.setCadence(RecurringSeries.Cadence.QUARTERLY);
        plan.setLastSeenAt(day(2026, 6, 30));
        stubSeries(plan);

        ForecastResponse response = service.forecast(EMAIL, 4, null, null, TODAY);

        assertThat(RecurringSeriesService.nextDueDate(plan)).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(response.months().get(0).items()).isEmpty(); // agosto
        assertThat(response.months().get(1).items()).isEmpty(); // setembro
        assertThat(response.months().get(2).items()).singleElement()
                .extracting(ForecastItemResponse::dueDay).isEqualTo(2); // outubro
        assertThat(response.months().get(3).items()).isEmpty(); // novembro
    }

    @Test
    void cumulativeNetStartsFromTheInformedBalance() {
        RecurringSeries rent = monthly("aluguel", "Aluguel", RecurringSeries.Flow.EXPENSE,
                5, "1500.00", RecurringSeries.Source.USER);
        RecurringSeries salary = monthly("salario", "Salário", RecurringSeries.Flow.INCOME,
                30, "4500.00", RecurringSeries.Source.DETECTED);
        stubSeries(rent, salary);

        ForecastResponse response = service.forecast(EMAIL, 2, new BigDecimal("-1000.00"), null, TODAY);

        assertThat(response.startingBalance()).isEqualByComparingTo("-1000.00");
        // o "saldo previsto negativo/positivo" do dono: baseline + líquido de cada mês
        assertThat(response.months().get(0).cumulativeNet()).isEqualByComparingTo("2000.00");
        assertThat(response.months().get(1).cumulativeNet()).isEqualByComparingTo("5000.00");
    }

    @Test
    void monthsRangeIsValidatedAndDefaultsToThree() {
        stubSeries();

        assertThatThrownBy(() -> service.forecast(EMAIL, 0, null, null, TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre 1 e 12");
        assertThatThrownBy(() -> service.forecast(EMAIL, 13, null, null, TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entre 1 e 12");

        ForecastResponse response = service.forecast(EMAIL, null, null, null, TODAY);
        assertThat(response.months()).hasSize(3);
        assertThat(response.months().get(0).month()).isEqualTo("2026-08");
        assertThat(response.months().get(2).month()).isEqualTo("2026-10");
        assertThat(response.startingBalance()).isNull();
    }

    // ------------------------------------------------------------------
    // EC-116 — projeção alinhada à âncora do ciclo
    // ------------------------------------------------------------------

    @Test
    void withoutWindowEachPeriodStillDeclaresTheCalendarMonthItCovers() {
        // retrocompatibilidade: o APK publicado não manda recorte nenhum e
        // continua recebendo os mesmos meses e os mesmos números — start/end só
        // tornam explícito o recorte que já era usado
        RecurringSeries rent = monthly("aluguel", "Aluguel", RecurringSeries.Flow.EXPENSE,
                5, "1500.00", RecurringSeries.Source.USER);
        stubSeries(rent);

        ForecastResponse response = service.forecast(EMAIL, 2, null, null, TODAY);

        // mês do calendário é o ciclo que abre todo dia 1
        assertThat(response.anchorDay()).isEqualTo(1);
        ForecastMonthResponse august = response.months().get(0);
        assertThat(august.month()).isEqualTo("2026-08");
        assertThat(august.start()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(august.end()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(august.items().get(0).dueDay()).isEqualTo(5);
        assertThat(august.items().get(0).dueDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        ForecastMonthResponse september = response.months().get(1);
        assertThat(september.start()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(september.end()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void theThreeWaysOfAskingForTheCurrentCalendarMonthAgreeToTheLastCent() {
        // sem recorte, com month=2026-08 e com a janela 01/08→31/08: é o MESMO
        // período, então tem que ser a mesma resposta — inclusive no equivalente
        // semanal, que no mês do calendário continua sendo 52/12 e não dias/7
        RecurringSeries groceries = weekly("feira", "Feira", "100.00", day(2026, 8, 8));
        stubSeries(groceries);

        ForecastResponse implicit = service.forecast(EMAIL, 2, null, null, TODAY);
        ForecastResponse byMonth = service.forecast(EMAIL, 2, null,
                AnalysisWindow.ofMonth(YearMonth.of(2026, 8)), TODAY);
        ForecastResponse byWindow = service.forecast(EMAIL, 2, null,
                AnalysisWindow.of(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)), TODAY);

        assertThat(byMonth).isEqualTo(implicit);
        assertThat(byWindow).isEqualTo(implicit);
        assertThat(implicit.anchorDay()).isEqualTo(1);
        assertThat(implicit.months().get(0).items().get(0).amount()).isEqualByComparingTo("433.33");
    }

    @Test
    void theAnchoredCycleIsProjectedExactlyAsTheAppDrewIt() {
        stubSeries();

        ForecastResponse response = service.forecast(EMAIL, 3, null, cycleContaining(12, TODAY), TODAY);

        assertThat(response.anchorDay()).isEqualTo(12);
        assertThat(response.months()).extracting(ForecastMonthResponse::month)
                .containsExactly("2026-08", "2026-09", "2026-10");
        assertThat(response.months()).extracting(ForecastMonthResponse::start)
                .containsExactly(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 9, 12),
                        LocalDate.of(2026, 10, 12));
        assertThat(response.months()).extracting(ForecastMonthResponse::end)
                .containsExactly(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 10, 11),
                        LocalDate.of(2026, 11, 11));
    }

    @Test
    void beforeTheAnchorTheCurrentPeriodIsTheCycleThatOpenedLastMonth() {
        stubSeries();

        // hoje é 05/08 com âncora 12: o app ainda mostra o ciclo 12/07 → 11/08 e
        // é ele que vai na requisição — o servidor não recorta por conta própria
        ForecastResponse response = service.forecast(EMAIL, 1, null,
                cycleContaining(12, LocalDate.of(2026, 8, 5)), LocalDate.of(2026, 8, 5));

        ForecastMonthResponse current = response.months().get(0);
        assertThat(current.month()).isEqualTo("2026-07");
        assertThat(current.start()).isEqualTo(LocalDate.of(2026, 7, 12));
        assertThat(current.end()).isEqualTo(LocalDate.of(2026, 8, 11));
    }

    @Test
    void anchor31WalksThroughFebruaryAndThirtyDayMonthsWithoutGaps() {
        stubSeries();

        ForecastResponse response = service.forecast(EMAIL, 4, null,
                cycleContaining(31, LocalDate.of(2026, 1, 31)), LocalDate.of(2026, 1, 31));

        // a âncora 31 sobrevive à passagem por fevereiro, que encurtou a janela
        // pedida para 31/01 → 27/02
        assertThat(response.anchorDay()).isEqualTo(31);
        assertThat(response.months()).extracting(ForecastMonthResponse::month)
                .containsExactly("2026-01", "2026-02", "2026-03", "2026-04");
        // 31/01→27/02 (fevereiro tem 28), 28/02→30/03, 31/03→29/04 (abril tem 30),
        // 30/04→30/05
        assertThat(response.months()).extracting(ForecastMonthResponse::start)
                .containsExactly(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
                        LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30));
        assertThat(response.months()).extracting(ForecastMonthResponse::end)
                .containsExactly(LocalDate.of(2026, 2, 27), LocalDate.of(2026, 3, 30),
                        LocalDate.of(2026, 4, 29), LocalDate.of(2026, 5, 30));
    }

    @Test
    void anchor31StartingFromFebruaryStillReachesTheThirtyFirstInMarch() {
        stubSeries();

        // a janela pedida é a encurtada (28/02 → 30/03): se o servidor lesse a
        // âncora só pela abertura, ela viraria 28 e nunca mais voltaria ao 31
        ForecastResponse response = service.forecast(EMAIL, 2, null,
                cycleContaining(31, LocalDate.of(2026, 3, 1)), LocalDate.of(2026, 3, 1));

        assertThat(response.anchorDay()).isEqualTo(31);
        assertThat(response.months()).extracting(ForecastMonthResponse::start)
                .containsExactly(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31));
        assertThat(response.months()).extracting(ForecastMonthResponse::end)
                .containsExactly(LocalDate.of(2026, 3, 30), LocalDate.of(2026, 4, 29));
    }

    @Test
    void cyclesCrossTheYearTurnAndCarryTheJanuaryChargeIntoTheDecemberCycle() {
        RecurringSeries internet = monthly("fibrax", "Internet", RecurringSeries.Flow.EXPENSE,
                5, "100.00", RecurringSeries.Source.DETECTED);
        internet.setLastSeenAt(day(2026, 12, 5));
        stubSeries(internet);

        ForecastResponse response = service.forecast(EMAIL, 2, null,
                cycleContaining(12, LocalDate.of(2026, 12, 20)), LocalDate.of(2026, 12, 20));

        ForecastMonthResponse december = response.months().get(0);
        assertThat(december.month()).isEqualTo("2026-12");
        assertThat(december.start()).isEqualTo(LocalDate.of(2026, 12, 12));
        assertThat(december.end()).isEqualTo(LocalDate.of(2027, 1, 11));
        // a cobrança de 05/01/2027 pertence ao ciclo que abriu em dezembro
        assertThat(december.items()).singleElement()
                .extracting(ForecastItemResponse::dueDate).isEqualTo(LocalDate.of(2027, 1, 5));
        assertThat(december.expectedExpense()).isEqualByComparingTo("100.00");

        ForecastMonthResponse january = response.months().get(1);
        assertThat(january.month()).isEqualTo("2027-01");
        assertThat(january.start()).isEqualTo(LocalDate.of(2027, 1, 12));
        assertThat(january.end()).isEqualTo(LocalDate.of(2027, 2, 11));
        assertThat(january.items()).singleElement()
                .extracting(ForecastItemResponse::dueDate).isEqualTo(LocalDate.of(2027, 2, 5));
    }

    @Test
    void weeklyIsProratedByTheRealLengthOfACycleThatIsNotACalendarMonth() {
        // o fator 4,33 é a média do mês do calendário; num ciclo de 28 dias ele
        // inflaria ~8% um período que só tem 4 semanas, e num de 31 faltaria
        RecurringSeries groceries = weekly("feira", "Feira", "100.00", day(2026, 1, 30));
        stubSeries(groceries);

        ForecastResponse response = service.forecast(EMAIL, 2, null,
                cycleContaining(31, LocalDate.of(2026, 1, 31)), LocalDate.of(2026, 1, 31));

        // 31/01 → 27/02 são 28 dias: 100 × 28 ÷ 7 = 400,00
        assertThat(response.months().get(0).items().get(0).amount()).isEqualByComparingTo("400.00");
        // 28/02 → 30/03 são 31 dias: 100 × 31 ÷ 7 = 442,857… → 442,86
        assertThat(response.months().get(1).items().get(0).amount()).isEqualByComparingTo("442.86");
        assertThat(response.months().get(1).expectedExpense()).isEqualByComparingTo("442.86");
    }

    @Test
    void weeklyKeepsTheHistoricalFactorWhenNoWindowIsInformed() {
        RecurringSeries groceries = weekly("feira", "Feira", "100.00", day(2026, 1, 30));
        stubSeries(groceries);

        ForecastResponse response = service.forecast(EMAIL, 2, null, null, LocalDate.of(2026, 1, 31));

        // janeiro tem 31 dias, mas o mês do calendário segue valendo 52/12
        assertThat(response.months().get(0).items().get(0).amount()).isEqualByComparingTo("433.33");
        assertThat(response.months().get(1).items().get(0).amount()).isEqualByComparingTo("433.33");
    }

    @Test
    void aWindowOfThirtyTwoDaysIsHonoredLiterallyAndCountsThirtyTwoDaysOfWeeks() {
        // janela fechando NO dia da âncora (12/07 a 12/08 = 32 dias): é o recorte
        // que o cliente pediu e é o que ele recebe, sem correção silenciosa. O
        // ciclo seguinte abre no dia 13 porque foi isso que o pedido disse, e o
        // eco de anchorDay=13 é como o cliente enxerga o próprio erro de um dia.
        RecurringSeries groceries = weekly("feira", "Feira", "100.00", day(2026, 7, 10));
        stubSeries(groceries);

        ForecastResponse response = service.forecast(EMAIL, 2, null,
                AnalysisWindow.of(LocalDate.of(2026, 7, 12), LocalDate.of(2026, 8, 12)), TODAY);

        assertThat(response.anchorDay()).isEqualTo(13);
        assertThat(response.months().get(0).start()).isEqualTo(LocalDate.of(2026, 7, 12));
        assertThat(response.months().get(0).end()).isEqualTo(LocalDate.of(2026, 8, 12));
        // 100 × 32 ÷ 7 = 457,142… → 457,14 (com 4,33 seriam 433,33: ~5% a menos
        // de feira num período que tem 4,57 semanas)
        assertThat(response.months().get(0).items().get(0).amount()).isEqualByComparingTo("457.14");
        // e o período seguinte volta ao tamanho de um ciclo: 13/08 → 12/09, 31 dias
        assertThat(response.months().get(1).start()).isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(response.months().get(1).end()).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(response.months().get(1).items().get(0).amount()).isEqualByComparingTo("442.86");
    }

    @Test
    void quarterlyChargeOnTheCycleBoundaryBelongsToTheCycleThatOpensOnIt() {
        // duas cobranças trimestrais na fronteira do MESMO ciclo 12/08 → 11/09:
        // uma no primeiro dia, outra no último
        RecurringSeries plan = monthly("zetacel", "Plano Zetacel", RecurringSeries.Flow.EXPENSE,
                12, "69.90", RecurringSeries.Source.DETECTED);
        plan.setCadence(RecurringSeries.Cadence.QUARTERLY);
        plan.setLastSeenAt(day(2026, 5, 12));
        RecurringSeries insurance = monthly("seguro", "Seguro", RecurringSeries.Flow.EXPENSE,
                11, "180.00", RecurringSeries.Source.USER);
        insurance.setCadence(RecurringSeries.Cadence.QUARTERLY);
        insurance.setLastSeenAt(day(2026, 6, 11));
        stubSeries(plan, insurance);

        ForecastResponse response = service.forecast(EMAIL, 4, null, cycleContaining(12, TODAY), TODAY);

        ForecastMonthResponse current = response.months().get(0);
        assertThat(current.start()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(current.end()).isEqualTo(LocalDate.of(2026, 9, 11));
        // ordenação pela DATA: 12/08 (dia 12) antes de 11/09 (dia 11) — pelo dia
        // do mês a ordem sairia invertida
        assertThat(current.items()).extracting(ForecastItemResponse::dueDate)
                .containsExactly(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 9, 11));
        assertThat(current.expectedExpense()).isEqualByComparingTo("249.90");
        assertThat(response.months().get(1).items()).isEmpty();
        assertThat(response.months().get(2).items()).isEmpty();
        // o trimestre seguinte de cada uma cai no ciclo 12/11 → 11/12, de novo uma
        // na abertura e outra no fechamento
        assertThat(response.months().get(3).items()).extracting(ForecastItemResponse::dueDate)
                .containsExactly(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 12, 11));
        assertThat(response.months().get(3).expectedExpense()).isEqualByComparingTo("249.90");
    }

    @Test
    void currentCycleSettlesOnlyWhatFellInsideItsOwnWindow() {
        // o aluguel caiu em 05/08. Com âncora 12 e hoje 15/08, esse pagamento é
        // do ciclo ANTERIOR (12/07 → 11/08): o ciclo corrente ainda espera o de
        // 05/09 inteiro. No mês do calendário ele liquida agosto, como sempre.
        RecurringSeries rent = monthly("aluguel", "Aluguel", RecurringSeries.Flow.EXPENSE,
                5, "1500.00", RecurringSeries.Source.USER);
        rent.setLastSeenAt(day(2026, 8, 5));
        stubSeries(rent);
        OffsetDateTime paidAt = day(2026, 8, 5);
        when(linkRepository.countBySeriesIdInPeriod(anyList(), any(), any()))
                .thenAnswer(invocation -> {
                    OffsetDateTime from = invocation.getArgument(1);
                    OffsetDateTime to = invocation.getArgument(2);
                    return !paidAt.isBefore(from) && paidAt.isBefore(to)
                            ? List.of(occurrences(rent.getId(), 1))
                            : List.of();
                });

        ForecastResponse byCycle = service.forecast(EMAIL, 1, null, cycleContaining(12, TODAY), TODAY);

        ArgumentCaptor<OffsetDateTime> from = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> to = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(linkRepository, atLeastOnce())
                .countBySeriesIdInPeriod(anyList(), from.capture(), to.capture());
        // a contagem recorta o ciclo, não o mês: [12/08, 12/09) em UTC
        assertThat(from.getValue()).isEqualTo(OffsetDateTime.parse("2026-08-12T00:00:00Z"));
        assertThat(to.getValue()).isEqualTo(OffsetDateTime.parse("2026-09-12T00:00:00Z"));
        assertThat(byCycle.months().get(0).items()).singleElement()
                .extracting(ForecastItemResponse::settled).isEqualTo(false);
        assertThat(byCycle.months().get(0).items().get(0).dueDate())
                .isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(byCycle.months().get(0).expectedExpense()).isEqualByComparingTo("1500.00");

        ForecastResponse byMonth = service.forecast(EMAIL, 1, null, null, TODAY);

        assertThat(byMonth.months().get(0).items()).singleElement()
                .extracting(ForecastItemResponse::settled).isEqualTo(true);
        assertThat(byMonth.months().get(0).expectedExpense()).isEqualByComparingTo("0.00");
    }

    @Test
    void theCurrentCycleOpenedBeforeTodayAndProjectsWhatIsStillOpenInIt() {
        // ciclo corrente parcial: 12/08 → 11/09 com hoje em 15/08. O que já foi
        // conciliado dentro DELE sai da soma; o que venceu antes de hoje mas não
        // apareceu no extrato continua previsto — atrasado não é pago.
        RecurringSeries internet = monthly("fibrax", "Internet", RecurringSeries.Flow.EXPENSE,
                13, "100.00", RecurringSeries.Source.DETECTED);
        internet.setLastSeenAt(day(2026, 8, 13));
        RecurringSeries card = monthly("faturax", "Cartão", RecurringSeries.Flow.EXPENSE,
                14, "800.00", RecurringSeries.Source.DETECTED);
        card.setLastSeenAt(day(2026, 7, 14));
        stubSeries(internet, card);
        when(linkRepository.countBySeriesIdInPeriod(anyList(), any(), any()))
                .thenReturn(List.of(occurrences(internet.getId(), 1)));

        ForecastResponse response = service.forecast(EMAIL, 1, null, cycleContaining(12, TODAY), TODAY);

        ForecastMonthResponse current = response.months().get(0);
        assertThat(current.start()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(current.items()).extracting(ForecastItemResponse::displayName,
                        ForecastItemResponse::dueDate, ForecastItemResponse::settled)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Internet", LocalDate.of(2026, 8, 13), true),
                        org.assertj.core.groups.Tuple.tuple("Cartão", LocalDate.of(2026, 8, 14), false));
        assertThat(current.expectedExpense()).isEqualByComparingTo("800.00");
    }

    @Test
    void itemsOfACycleAreOrderedByTheDateTheyFallOn() {
        RecurringSeries card = monthly("faturax", "Cartão", RecurringSeries.Flow.EXPENSE,
                20, "800.00", RecurringSeries.Source.DETECTED);
        card.setLastSeenAt(day(2026, 7, 20));
        RecurringSeries rent = monthly("aluguel", "Aluguel", RecurringSeries.Flow.EXPENSE,
                5, "1500.00", RecurringSeries.Source.USER);
        rent.setLastSeenAt(day(2026, 8, 5));
        stubSeries(card, rent);

        ForecastResponse response = service.forecast(EMAIL, 1, null, cycleContaining(12, TODAY), TODAY);

        assertThat(response.months().get(0).items())
                .extracting(ForecastItemResponse::displayName)
                .containsExactly("Cartão", "Aluguel");
        assertThat(response.months().get(0).items())
                .extracting(ForecastItemResponse::dueDay)
                .containsExactly(20, 5);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /**
     * A janela que o app manda: o ciclo ancorado que CONTÉM a data. É a
     * transcrição de {@code cycleWindowContaining} do app — escrita aqui à mão,
     * e não reaproveitada da produção, justamente para o teste falhar se as duas
     * contas divergirem.
     */
    private static AnalysisWindow cycleContaining(int anchorDay, LocalDate date) {
        YearMonth month = YearMonth.from(date);
        if (date.getDayOfMonth() < Math.min(anchorDay, month.lengthOfMonth())) {
            month = month.minusMonths(1);
        }
        YearMonth nextMonth = month.plusMonths(1);
        LocalDate start = month.atDay(Math.min(anchorDay, month.lengthOfMonth()));
        LocalDate nextStart = nextMonth.atDay(Math.min(anchorDay, nextMonth.lengthOfMonth()));
        return AnalysisWindow.of(start, nextStart.minusDays(1));
    }

    private RecurringSeries monthly(String key, String name, RecurringSeries.Flow flow,
                                    Integer anchorDay, String amount, RecurringSeries.Source source) {
        return RecurringSeries.builder()
                .id(UUID.randomUUID()).user(user)
                .merchantKey(key).displayName(name)
                .flow(flow)
                .cadence(RecurringSeries.Cadence.MONTHLY)
                .anchorDay(anchorDay != null ? anchorDay.shortValue() : null)
                .amountType(RecurringSeries.AmountType.FIXED)
                .expectedAmount(new BigDecimal(amount))
                .occurrences(3).active(true)
                .source(source)
                .build();
    }

    private RecurringSeries weekly(String key, String name, String amount, OffsetDateTime lastSeenAt) {
        return RecurringSeries.builder()
                .id(UUID.randomUUID()).user(user)
                .merchantKey(key).displayName(name)
                .flow(RecurringSeries.Flow.EXPENSE)
                .cadence(RecurringSeries.Cadence.WEEKLY)
                .amountType(RecurringSeries.AmountType.FIXED)
                .expectedAmount(new BigDecimal(amount))
                .occurrences(8).active(true)
                .source(RecurringSeries.Source.DETECTED)
                .lastSeenAt(lastSeenAt)
                .build();
    }

    private void stubSeries(RecurringSeries... series) {
        when(seriesRepository.findAllByUserId(user.getId())).thenReturn(List.of(series));
    }

    private RecurringSeriesLinkRepository.SeriesOccurrences occurrences(UUID seriesId, long count) {
        return new RecurringSeriesLinkRepository.SeriesOccurrences() {
            @Override
            public UUID getSeriesId() {
                return seriesId;
            }

            @Override
            public long getOccurrences() {
                return count;
            }
        };
    }

    private OffsetDateTime day(int year, int month, int dayOfMonth) {
        return OffsetDateTime.of(LocalDate.of(year, month, dayOfMonth), LocalTime.NOON, ZoneOffset.UTC);
    }
}
