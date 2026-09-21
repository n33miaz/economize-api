package br.com.economize.service.wish;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lógica pura da recomendação — datas fixas de 2026, com 03/10/2026 como
 * sábado confirmado (é o mesmo ponto de referência do plano v1: "04/10 é
 * domingo").
 */
class PurchaseAdvisorTest {

    private final PurchaseAdvisor advisor = new PurchaseAdvisor();

    @Test
    @DisplayName("Mensal + fim de semana: o melhor dia é o primeiro sábado/domingo a partir do latest")
    void cashWithWeekendPreferencePicksFirstWeekend() {
        // 30/09/2026 é quarta-feira; o próximo fim de semana é sábado 03/10
        LocalDate latest = LocalDate.of(2026, 9, 30);
        assertThat(advisor.bestDayCash(latest, true)).isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test
    @DisplayName("Sem preferência de fim de semana, o melhor dia é o próprio latest")
    void cashWithoutWeekendPreferenceUsesFundingDateDirectly() {
        LocalDate latest = LocalDate.of(2026, 9, 30);
        assertThat(advisor.bestDayCash(latest, false)).isEqualTo(latest);
    }

    @Test
    @DisplayName("Um latest que já cai no fim de semana não se move")
    void cashLatestAlreadyOnWeekendStays() {
        LocalDate saturday = LocalDate.of(2026, 10, 3);
        assertThat(advisor.bestDayCash(saturday, true)).isEqualTo(saturday);
    }

    @Test
    @DisplayName("Cartão que fecha dia 10: bestDay é 11, invoiceDue no mês seguinte, sem aviso quando o salário paga a tempo")
    void cardAdviceHappyPath() {
        LocalDate today = LocalDate.of(2026, 10, 1);
        List<LocalDate> salaryDates = List.of(LocalDate.of(2026, 11, 9));

        PurchaseAdvisor.CardRecommendation rec =
                advisor.cardAdvice(10, 17, today, false, salaryDates);

        assertThat(rec.nextClosing()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(rec.bestDay()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(rec.invoiceDue()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(rec.paidBySalaryOn()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(rec.warning()).isNull();
    }

    @Test
    @DisplayName("Cartão cuja fatura vence antes de qualquer salário cair gera aviso")
    void cardAdviceWarnsWhenInvoiceDueBeforeAnySalary() {
        LocalDate today = LocalDate.of(2026, 10, 1);
        // nenhum salário cai entre o fechamento (10/10) e o vencimento (17/11)
        List<LocalDate> salaryDates = List.of(LocalDate.of(2026, 11, 20));

        PurchaseAdvisor.CardRecommendation rec =
                advisor.cardAdvice(10, 17, today, false, salaryDates);

        assertThat(rec.paidBySalaryOn()).isNull();
        assertThat(rec.warning()).isNotBlank();
    }

    @Test
    @DisplayName("Sem dia de vencimento conhecido, não há invoiceDue nem aviso")
    void cardAdviceWithoutDueDayHasNoInvoiceDue() {
        LocalDate today = LocalDate.of(2026, 10, 1);
        PurchaseAdvisor.CardRecommendation rec =
                advisor.cardAdvice(10, null, today, false, List.of());

        assertThat(rec.invoiceDue()).isNull();
        assertThat(rec.warning()).isNull();
    }

    @Test
    @DisplayName("Semanal: quatro próximas datas no dia da semana preferido")
    void weeklyNextDatesReturnsFourDates() {
        LocalDate from = LocalDate.of(2026, 9, 30); // quarta
        List<LocalDate> dates = advisor.weeklyNextDates(DayOfWeek.SATURDAY, from, 4);

        assertThat(dates).containsExactly(
                LocalDate.of(2026, 10, 3),
                LocalDate.of(2026, 10, 10),
                LocalDate.of(2026, 10, 17),
                LocalDate.of(2026, 10, 24));
    }

    @Test
    @DisplayName("Uma compra por mês infere cadência MONTHLY")
    void inferCadenceOncePerMonthIsMonthly() {
        List<PurchaseAdvisor.PurchaseRecord> purchases = List.of(
                purchase(LocalDate.of(2026, 7, 4), "500.00"),
                purchase(LocalDate.of(2026, 8, 1), "500.00"),
                purchase(LocalDate.of(2026, 9, 5), "500.00"));

        PurchaseAdvisor.CadenceInference inference = advisor.inferCadence(purchases, List.of(), 3);

        assertThat(inference.cadence()).isEqualTo("MONTHLY");
    }

    @Test
    @DisplayName("Quatro compras por mês infere cadência WEEKLY")
    void inferCadenceFourTimesPerMonthIsWeekly() {
        List<PurchaseAdvisor.PurchaseRecord> purchases = List.of(
                purchase(LocalDate.of(2026, 9, 5), "150.00"),
                purchase(LocalDate.of(2026, 9, 12), "150.00"),
                purchase(LocalDate.of(2026, 9, 19), "150.00"),
                purchase(LocalDate.of(2026, 9, 26), "150.00"));

        PurchaseAdvisor.CadenceInference inference = advisor.inferCadence(purchases, List.of(), 1);

        assertThat(inference.cadence()).isEqualTo("WEEKLY");
    }

    @Test
    @DisplayName("Duas compras por mês é ambíguo: supõe MONTHLY com confiança baixa")
    void inferCadenceTwicePerMonthGuessesMonthlyLow() {
        List<PurchaseAdvisor.PurchaseRecord> purchases = List.of(
                purchase(LocalDate.of(2026, 8, 3), "300.00"),
                purchase(LocalDate.of(2026, 8, 17), "300.00"),
                purchase(LocalDate.of(2026, 9, 4), "300.00"),
                purchase(LocalDate.of(2026, 9, 18), "300.00"));

        PurchaseAdvisor.CadenceInference inference = advisor.inferCadence(purchases, List.of(), 2);

        assertThat(inference.cadence()).isEqualTo("MONTHLY");
        assertThat(inference.confidence()).isEqualTo("LOW");
    }

    @Test
    @DisplayName("weekendShare mede a parcela do VALOR gasto em sábado/domingo")
    void weekendShareIsFractionOfAmount() {
        List<PurchaseAdvisor.PurchaseRecord> purchases = List.of(
                purchase(LocalDate.of(2026, 10, 3), "800.00"),  // sábado
                purchase(LocalDate.of(2026, 10, 14), "200.00")); // quarta

        PurchaseAdvisor.CadenceInference inference = advisor.inferCadence(purchases, List.of(), 1);

        assertThat(inference.weekendShare()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("weekendShare é zero quando o total gasto no período é zero")
    void weekendShareIsZeroWhenTotalAmountIsZero() {
        List<PurchaseAdvisor.PurchaseRecord> purchases = List.of(purchase(LocalDate.of(2026, 10, 3), "0.00"));

        PurchaseAdvisor.CadenceInference inference = advisor.inferCadence(purchases, List.of(), 1);

        assertThat(inference.weekendShare()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("daysAfterLanding é a mediana de dias úteis entre a queda mais recente e cada compra")
    void daysAfterLandingIsMedianOfBusinessDaysSinceLastLanding() {
        List<LocalDate> landings = List.of(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 10, 2));
        List<PurchaseAdvisor.PurchaseRecord> purchases = List.of(
                // 04/09 (sexta) -> 08/09 (terça, pois 07/09 é feriado): 1 dia útil depois
                purchase(LocalDate.of(2026, 9, 8), "100.00"),
                // 02/10 (sexta) -> 06/10 (terça): 2 dias úteis depois
                purchase(LocalDate.of(2026, 10, 6), "100.00"));

        PurchaseAdvisor.CadenceInference inference = advisor.inferCadence(purchases, landings, 2);

        assertThat(inference.daysAfterLanding()).isEqualTo(2);
    }

    @Test
    @DisplayName("Sem nenhuma queda antes de qualquer compra, daysAfterLanding fica nulo")
    void daysAfterLandingIsNullWithoutAnyLandingBeforeAPurchase() {
        List<LocalDate> landings = List.of(LocalDate.of(2026, 11, 1));
        List<PurchaseAdvisor.PurchaseRecord> purchases = List.of(purchase(LocalDate.of(2026, 9, 8), "100.00"));

        PurchaseAdvisor.CadenceInference inference = advisor.inferCadence(purchases, landings, 1);

        assertThat(inference.daysAfterLanding()).isNull();
    }

    @Test
    @DisplayName("dueDate rola para o mês seguinte quando o dia de vencimento antecede o fechamento")
    void dueDateRollsOverToNextMonthWhenDueDayIsBeforeClosingDay() {
        LocalDate closing = LocalDate.of(2026, 10, 25);
        assertThat(advisor.dueDate(closing, 5)).isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    @DisplayName("dueDate fica no mesmo mês quando o dia de vencimento é depois do fechamento")
    void dueDateStaysInTheSameMonthWhenDueDayIsAfterClosingDay() {
        LocalDate closing = LocalDate.of(2026, 10, 10);
        assertThat(advisor.dueDate(closing, 17)).isEqualTo(LocalDate.of(2026, 10, 17));
    }

    private PurchaseAdvisor.PurchaseRecord purchase(LocalDate date, String amount) {
        return new PurchaseAdvisor.PurchaseRecord(date, new BigDecimal(amount).negate());
    }
}
