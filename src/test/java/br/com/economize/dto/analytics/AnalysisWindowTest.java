package br.com.economize.dto.analytics;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisWindowTest {

    @Test
    void monthKeepsTheOldContractIncludingTheCalendarComparison() {
        AnalysisWindow window = AnalysisWindow.resolve("2026-07", null, null);

        assertThat(window.monthLabel()).isEqualTo("2026-07");
        assertThat(window.start()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(window.end()).isEqualTo(LocalDate.of(2026, 7, 31));
        // o instante final é o começo de agosto: sem isso, o dia 31 sumiria da conta
        assertThat(window.endExclusiveInstant())
                .isEqualTo(OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC));

        AnalysisWindow previous = window.previous();
        assertThat(previous.monthLabel()).isEqualTo("2026-06");
        assertThat(previous.start()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(previous.end()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void anchoredWindowComparesWithAWindowOfTheSameLength() {
        // ciclo do salário: 12/07 a 12/08 são 32 dias inclusivos
        AnalysisWindow window = AnalysisWindow.resolve(null, "2026-07-12", "2026-08-12");

        assertThat(window.monthLabel()).isNull();
        assertThat(window.lengthInDays()).isEqualTo(32);

        AnalysisWindow previous = window.previous();
        assertThat(previous.lengthInDays()).isEqualTo(window.lengthInDays());
        assertThat(previous.end()).isEqualTo(LocalDate.of(2026, 7, 11));
        assertThat(previous.start()).isEqualTo(LocalDate.of(2026, 6, 10));
        // as duas janelas não se sobrepõem em um único dia
        assertThat(previous.end()).isEqualTo(window.start().minusDays(1));
    }

    @Test
    void previousWindowSurvivesFebruaryAndTheYearBoundary() {
        // janela que atravessa a virada do ano e compara contra fevereiro:
        // fosse "mês anterior do calendário", 31 dias virariam 28 e a variação
        // mentiria ~10% sem nenhuma mudança de gasto
        AnalysisWindow window = AnalysisWindow.resolve(null, "2027-01-05", "2027-02-04");
        AnalysisWindow previous = window.previous();

        assertThat(window.lengthInDays()).isEqualTo(31);
        assertThat(previous.start()).isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(previous.end()).isEqualTo(LocalDate.of(2027, 1, 4));
        assertThat(previous.lengthInDays()).isEqualTo(31);
    }

    @Test
    void singleDayWindowIsValidAndComparesWithThePreviousDay() {
        AnalysisWindow window = AnalysisWindow.resolve(null, "2026-08-15", "2026-08-15");

        assertThat(window.lengthInDays()).isEqualTo(1);
        assertThat(window.startInstant())
                .isEqualTo(OffsetDateTime.of(2026, 8, 15, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(window.endExclusiveInstant())
                .isEqualTo(OffsetDateTime.of(2026, 8, 16, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(window.previous().start()).isEqualTo(LocalDate.of(2026, 8, 14));
        assertThat(window.previous().end()).isEqualTo(LocalDate.of(2026, 8, 14));
    }

    @Test
    void nothingInformedResolvesToNullSoEachEndpointKeepsItsOwnDefault() {
        assertThat(AnalysisWindow.resolve(null, null, null)).isNull();
        assertThat(AnalysisWindow.resolve("  ", "", null)).isNull();
    }

    @Test
    void monthAndWindowTogetherAreRejected() {
        assertThatThrownBy(() -> AnalysisWindow.resolve("2026-07", "2026-07-12", "2026-08-12"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nunca os dois");
    }

    @Test
    void halfWindowIsRejected() {
        assertThatThrownBy(() -> AnalysisWindow.resolve(null, "2026-07-12", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("informados juntos");
        assertThatThrownBy(() -> AnalysisWindow.resolve(null, null, "2026-08-12"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("informados juntos");
    }

    @Test
    void endBeforeStartIsRejected() {
        assertThatThrownBy(() -> AnalysisWindow.resolve(null, "2026-08-12", "2026-07-12"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("end não pode ser anterior a start");
    }

    @Test
    void windowLongerThanAYearIsRejectedAndTheLimitItselfPasses() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        assertThatThrownBy(() -> AnalysisWindow.resolve(null,
                start.toString(), start.plusDays(AnalysisWindow.MAX_DAYS).toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Janela máxima de 366 dias");

        assertThat(AnalysisWindow.resolve(null,
                start.toString(), start.plusDays(AnalysisWindow.MAX_DAYS - 1).toString()).lengthInDays())
                .isEqualTo(AnalysisWindow.MAX_DAYS);
    }

    @Test
    void malformedDatesFailAsValidationNotAsServerError() {
        assertThatThrownBy(() -> AnalysisWindow.resolve(null, "12/07/2026", "2026-08-12"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("start inválido — use o formato YYYY-MM-DD");
        assertThatThrownBy(() -> AnalysisWindow.resolve(null, "2026-07-12", "ontem"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("end inválido — use o formato YYYY-MM-DD");
        assertThatThrownBy(() -> AnalysisWindow.resolve("julho", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Mês inválido — use o formato YYYY-MM");
    }

    @Test
    void ofMonthMatchesTheAggregationBoundsUsedBeforeTheWindow() {
        AnalysisWindow window = AnalysisWindow.ofMonth(YearMonth.of(2026, 2));

        assertThat(window.startInstant())
                .isEqualTo(OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(window.endExclusiveInstant())
                .isEqualTo(window.startInstant().plusMonths(1));
    }
}
