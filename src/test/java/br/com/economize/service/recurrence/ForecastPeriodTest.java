package br.com.economize.service.recurrence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aritmética do ciclo ancorado (EC-116), isolada do resto da previsão. É a
 * mesma conta que o app faz em {@code src/utils/cycleWindow.ts}; se as duas
 * divergirem, a Home volta a ter duas réguas.
 */
class ForecastPeriodTest {

    @Test
    void theFirstPeriodIsTheRequestedWindowLiterally() {
        ForecastPeriod cycle = ForecastPeriod.first(
                LocalDate.of(2026, 7, 12), LocalDate.of(2026, 8, 11));

        // o ciclo pertence ao mês em que ABRE, e o recorte é o que veio
        assertThat(cycle.month()).isEqualTo(YearMonth.of(2026, 7));
        assertThat(cycle.start()).isEqualTo(LocalDate.of(2026, 7, 12));
        assertThat(cycle.end()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(cycle.lengthInDays()).isEqualTo(31);
        assertThat(cycle.isCalendarMonth()).isFalse();
    }

    @Test
    void aWindowThatCoversTheWholeMonthIsAMonth() {
        ForecastPeriod february = ForecastPeriod.first(
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

        assertThat(february.isCalendarMonth()).isTrue();
        assertThat(february.lengthInDays()).isEqualTo(28);
        // e o ciclo de âncora 1 é exatamente isso, pelo recorte e não pela origem
        assertThat(ForecastPeriod.next(february, 1))
                .isEqualTo(ForecastPeriod.first(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)));
        assertThat(ForecastPeriod.next(february, 1).isCalendarMonth()).isTrue();
    }

    @Test
    void anchorOfReadsTheAnchorHiddenInTheWindow() {
        // ciclo comum: abre no 12, o seguinte também
        assertThat(ForecastPeriod.anchorOf(LocalDate.of(2026, 7, 12), LocalDate.of(2026, 8, 11)))
                .isEqualTo(12);
        // mês do calendário é o ciclo que abre todo dia 1
        assertThat(ForecastPeriod.anchorOf(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .isEqualTo(1);
        // âncora 31 nas duas travessias de fevereiro: numa a abertura é cheia
        // (31/01) e a seguinte é encurtada; na outra é o contrário
        assertThat(ForecastPeriod.anchorOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 27)))
                .isEqualTo(31);
        assertThat(ForecastPeriod.anchorOf(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 30)))
                .isEqualTo(31);
        // âncora 31 num mês de 30 dias
        assertThat(ForecastPeriod.anchorOf(LocalDate.of(2026, 4, 30), LocalDate.of(2026, 5, 30)))
                .isEqualTo(31);
        // âncoras 29 e 30 saindo de fevereiro, que encurtou as duas para o dia 28
        assertThat(ForecastPeriod.anchorOf(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 28)))
                .isEqualTo(29);
        assertThat(ForecastPeriod.anchorOf(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 29)))
                .isEqualTo(30);
    }

    @Test
    void anchor31WalksThroughFebruaryAndThirtyDayMonthsWithoutOpeningGaps() {
        // fevereiro (28), março (31) e abril (30) em sequência: a âncora encurta
        // onde precisa e o ciclo seguinte sempre abre na véspera+1 do anterior
        ForecastPeriod january = ForecastPeriod.first(
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 27));
        ForecastPeriod february = ForecastPeriod.next(january, 31);
        ForecastPeriod march = ForecastPeriod.next(february, 31);
        ForecastPeriod april = ForecastPeriod.next(march, 31);

        assertThat(january.lengthInDays()).isEqualTo(28);
        assertThat(february.start()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(february.end()).isEqualTo(LocalDate.of(2026, 3, 30));
        assertThat(march.start()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(march.end()).isEqualTo(LocalDate.of(2026, 4, 29));
        assertThat(april.start()).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(april.end()).isEqualTo(LocalDate.of(2026, 5, 30));
        // nenhum deles é mês do calendário, nem o que começa no dia 31
        assertThat(january.isCalendarMonth()).isFalse();
        assertThat(february.isCalendarMonth()).isFalse();
    }

    @Test
    void anchor31LandsOnTheLeapDayInAFebruaryOf29() {
        ForecastPeriod january = ForecastPeriod.first(
                LocalDate.of(2028, 1, 31), LocalDate.of(2028, 2, 28));
        ForecastPeriod february = ForecastPeriod.next(january, 31);

        assertThat(february.start()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(february.end()).isEqualTo(LocalDate.of(2028, 3, 30));
    }

    @Test
    void everyAnchorChainsContiguouslyAndSurvivesBeingReadBackForThreeYears() {
        // as duas garantias que sustentam a projeção inteira: entre o fim de um
        // ciclo e o começo do próximo não existe dia órfão nem dia contado duas
        // vezes, e a âncora lida de volta da janela é sempre a original — se ela
        // degradasse ao passar por fevereiro, as datas escorregariam para sempre
        for (int anchorDay = 1; anchorDay <= 31; anchorDay++) {
            YearMonth month = YearMonth.of(2026, 1);
            ForecastPeriod previous = ForecastPeriod.first(
                    ForecastPeriod.anchoredDay(month, anchorDay),
                    ForecastPeriod.anchoredDay(month.plusMonths(1), anchorDay).minusDays(1));
            for (int step = 1; step < 36; step++) {
                ForecastPeriod current = ForecastPeriod.next(previous, anchorDay);
                assertThat(current.start())
                        .as("âncora %d, ciclo %s", anchorDay, current.month())
                        .isEqualTo(previous.end().plusDays(1));
                assertThat(current.lengthInDays())
                        .as("âncora %d, ciclo %s", anchorDay, current.month())
                        .isBetween(28L, 31L);
                assertThat(ForecastPeriod.anchorOf(current.start(), current.end()))
                        .as("âncora %d relida do ciclo %s", anchorDay, current.month())
                        .isEqualTo(anchorDay);
                previous = current;
            }
        }
    }

    @Test
    void cycleCrossesTheYearTurnKeepingTheMonthItOpensIn() {
        ForecastPeriod november = ForecastPeriod.first(
                LocalDate.of(2026, 11, 12), LocalDate.of(2026, 12, 11));
        ForecastPeriod december = ForecastPeriod.next(november, 12);

        assertThat(december.month()).isEqualTo(YearMonth.of(2026, 12));
        assertThat(december.start()).isEqualTo(LocalDate.of(2026, 12, 12));
        assertThat(december.end()).isEqualTo(LocalDate.of(2027, 1, 11));
        assertThat(ForecastPeriod.next(december, 12).month()).isEqualTo(YearMonth.of(2027, 1));
    }

    @Test
    void aWindowThatIsNotACycleIsHonoredAndTheNextPeriodsFindTheRhythm() {
        // recorte que ninguém chamaria de ciclo (16 dias, abrindo no 20 e
        // fechando no 4): ele é respeitado como veio, e o encadeamento a partir
        // dele volta ao ritmo mensal em vez de repetir a esquisitice ou abrir
        // buraco. A âncora lida é a maior das duas aberturas, o dia 20.
        ForecastPeriod odd = ForecastPeriod.first(
                LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 4));
        int anchorDay = ForecastPeriod.anchorOf(odd.start(), odd.end());
        assertThat(anchorDay).isEqualTo(20);

        ForecastPeriod second = ForecastPeriod.next(odd, anchorDay);
        assertThat(second.start()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(second.end()).isEqualTo(LocalDate.of(2026, 9, 19));

        ForecastPeriod third = ForecastPeriod.next(second, anchorDay);
        assertThat(third.start()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(third.end()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(third.lengthInDays()).isEqualTo(30);
    }

    @Test
    void dayWithinFindsTheChargeDayOnEitherSideOfTheCycle() {
        ForecastPeriod cycle = ForecastPeriod.first(
                LocalDate.of(2026, 8, 12), LocalDate.of(2026, 9, 11));

        assertThat(cycle.dayWithin(20)).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(cycle.dayWithin(5)).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(cycle.dayWithin(12)).isEqualTo(cycle.start());
        assertThat(cycle.dayWithin(11)).isEqualTo(cycle.end());
    }

    @Test
    void dayWithinIsNullWhenTheShortCycleSimplyHasNoSuchDay() {
        // 31/01 → 27/02: o dia 30 pertence ao ciclo anterior (30/01) e ao
        // seguinte (28/02, encurtado) — nenhum dos dois é este
        ForecastPeriod january = ForecastPeriod.first(
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 27));

        assertThat(january.dayWithin(31)).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(january.dayWithin(30)).isNull();
    }

    @Test
    void dayWithinAlwaysAnswersInsideACalendarMonth() {
        ForecastPeriod february = ForecastPeriod.first(
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

        assertThat(february.dayWithin(31)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(february.dayWithin(1)).isEqualTo(LocalDate.of(2026, 2, 1));
    }

    @Test
    void containsIsInclusiveOnBothEnds() {
        ForecastPeriod cycle = ForecastPeriod.first(
                LocalDate.of(2026, 8, 12), LocalDate.of(2026, 9, 11));

        assertThat(cycle.contains(LocalDate.of(2026, 8, 11))).isFalse();
        assertThat(cycle.contains(LocalDate.of(2026, 8, 12))).isTrue();
        assertThat(cycle.contains(LocalDate.of(2026, 9, 11))).isTrue();
        assertThat(cycle.contains(LocalDate.of(2026, 9, 12))).isFalse();
    }
}
