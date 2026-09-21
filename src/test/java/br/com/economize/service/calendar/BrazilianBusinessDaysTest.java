package br.com.economize.service.calendar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O calendário de dias úteis bancários — datas verificadas manualmente contra
 * o calendário de feriados nacionais de 2026/2027 (ver Javadoc da classe).
 */
class BrazilianBusinessDaysTest {

    @Test
    @DisplayName("Páscoa por Meeus/Jones/Butcher bate com 2026 e 2027")
    void easterMatchesKnownDates() {
        assertThat(BrazilianBusinessDays.easter(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(BrazilianBusinessDays.easter(2027)).isEqualTo(LocalDate.of(2027, 3, 28));
    }

    @Test
    @DisplayName("Feriados móveis de 2026: Carnaval, Sexta Santa e Corpus Christi")
    void movableHolidays2026() {
        var holidays = BrazilianBusinessDays.holidays(2026);
        assertThat(holidays).contains(
                LocalDate.of(2026, 2, 16), // segunda de Carnaval
                LocalDate.of(2026, 2, 17), // terça de Carnaval
                LocalDate.of(2026, 4, 3),  // Sexta-feira Santa
                LocalDate.of(2026, 6, 4)); // Corpus Christi
    }

    @Test
    @DisplayName("20 de novembro só é feriado nacional a partir de 2024 (Lei 14.759/2023)")
    void blackAwarenessDayOnlyFrom2024() {
        assertThat(BrazilianBusinessDays.holidays(2024)).contains(LocalDate.of(2024, 11, 20));
        assertThat(BrazilianBusinessDays.holidays(2026)).contains(LocalDate.of(2026, 11, 20));
        assertThat(BrazilianBusinessDays.holidays(2023)).doesNotContain(LocalDate.of(2023, 11, 20));
    }

    @Test
    @DisplayName("nthBusinessDay dos meses de set/out/nov de 2026, com feriado adiantando o dia")
    void nthBusinessDayAroundHolidays() {
        // 07/09/2026 é segunda-feira e feriado (Independência) — o 5º dia útil desliza
        assertThat(BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 9), 5))
                .isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 10), 5))
                .isEqualTo(LocalDate.of(2026, 10, 7));
        // 12/10/2026 é segunda-feira e feriado (Nossa Senhora Aparecida)
        assertThat(BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 10), 8))
                .isEqualTo(LocalDate.of(2026, 10, 13));
        // 02/11/2026 é segunda-feira e feriado (Finados)
        assertThat(BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 11), 5))
                .isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    @DisplayName("businessDayOfMonth do VR real do dono (25/06/2026) é o 18º dia útil")
    void businessDayOfMonthMatchesRealFixture() {
        assertThat(BrazilianBusinessDays.businessDayOfMonth(LocalDate.of(2026, 6, 25))).isEqualTo(18);
    }

    @Test
    @DisplayName("businessDayOfMonth de um dia não útil é zero — não existe número inventado")
    void businessDayOfMonthOfWeekendIsZero() {
        // 2026-06-27 é sábado
        assertThat(BrazilianBusinessDays.businessDayOfMonth(LocalDate.of(2026, 6, 27))).isZero();
    }

    @Test
    @DisplayName("previous/nextBusinessDay num sábado andam para os dias úteis vizinhos")
    void previousAndNextBusinessDayAroundWeekend() {
        LocalDate saturday = LocalDate.of(2026, 6, 27);
        assertThat(BrazilianBusinessDays.nextBusinessDay(saturday)).isEqualTo(LocalDate.of(2026, 6, 29));
        assertThat(BrazilianBusinessDays.previousBusinessDay(saturday)).isEqualTo(LocalDate.of(2026, 6, 26));
    }

    @Test
    @DisplayName("plusBusinessDays negativo atravessa feriado sem contá-lo")
    void plusBusinessDaysNegativeCrossesHoliday() {
        // 08/09/2026 (terça) menos 1 dia útil pula 07/09 (feriado) e cai em 04/09 (sexta)
        assertThat(BrazilianBusinessDays.plusBusinessDays(LocalDate.of(2026, 9, 8), -1))
                .isEqualTo(LocalDate.of(2026, 9, 4));
    }

    @Test
    @DisplayName("businessDaysBetween é simétrico")
    void businessDaysBetweenIsSymmetric() {
        LocalDate a = LocalDate.of(2026, 6, 25);
        LocalDate b = LocalDate.of(2026, 7, 7);
        long forward = BrazilianBusinessDays.businessDaysBetween(a, b);
        long backward = BrazilianBusinessDays.businessDaysBetween(b, a);
        assertThat(forward).isEqualTo(-backward);
        assertThat(forward).isPositive();
    }

    @Test
    @DisplayName("businessDayOfMonth e businessDaysUntilMonthEnd de um dia não útil usam o dia útil anterior")
    void businessDaysUntilMonthEndCountsForwardFromTheDay() {
        // 04/09/2026 (sexta, 4º dia útil): faltam 17 dias úteis até o fim do mês
        assertThat(BrazilianBusinessDays.businessDaysUntilMonthEnd(LocalDate.of(2026, 9, 4))).isEqualTo(17);
    }
}
