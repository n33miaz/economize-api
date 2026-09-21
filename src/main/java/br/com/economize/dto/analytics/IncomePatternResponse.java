package br.com.economize.dto.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * O padrão de entradas em dias úteis e o melhor dia para as compras.
 *
 * <p>Campo nulo aqui quer dizer <b>"não sei"</b>, nunca zero — a mesma regra de
 * {@code WishResponses}. Toda data é o dia UTC do lançamento, no formato ISO.
 *
 * <p>Duas marcas viajam em tudo o que é número:
 * <ul>
 * <li>{@code origin}: MEASURED veio do extrato; INFORMED foi a pessoa que
 * declarou. O app mostra o selo — um dia informado sem histórico não pode
 * parecer um dia medido.</li>
 * <li>{@code confidence}: HIGH/MEDIUM/LOW pela quantidade de meses e pela
 * dispersão; nulo quando não há medida nenhuma.</li>
 * </ul>
 *
 * @param status  READY, INSUFFICIENT_HISTORY, NO_INCOME ou CARD_CYCLE_UNKNOWN
 * @param message a frase para a tela quando {@code status} não é READY
 * @param sources cada fonte de renda com o padrão observado e as próximas quedas
 * @param preference a preferência gravada; nulo quando nunca foi salva
 * @param inferred o que o extrato sugere — é o que o app usa quando não há preferência
 * @param advice  a recomendação; nulo quando {@code status} não é READY
 */
public record IncomePatternResponse(
        String status,
        String message,
        LocalDate today,
        List<SourcePattern> sources,
        Preference preference,
        Inferred inferred,
        Advice advice
) {

    /**
     * @param pattern  nulo quando a fonte ainda não tem três meses de histórico
     * @param relation só nas fontes que não são salário, quando há salário medido
     */
    public record SourcePattern(
            UUID incomeSourceId,
            UUID seriesId,
            String kind,
            String name,
            boolean confirmed,
            String origin,
            Pattern pattern,
            List<Occurrence> occurrences,
            List<Upcoming> upcoming,
            Relation relation
    ) {
    }

    /**
     * @param rule   BUSINESS_DAY_FROM_START, BUSINESS_DAY_FROM_END ou CALENDAR_DAY
     * @param median a posição típica na régua da regra (5 = 5º dia útil)
     * @param label  a frase pronta, em pt-BR ("por volta do 5º dia útil")
     */
    public record Pattern(
            String rule,
            Integer median,
            Integer min,
            Integer max,
            int monthsObserved,
            LocalDate firstOccurrence,
            LocalDate lastOccurrence,
            BigDecimal expectedAmount,
            String confidence,
            String label
    ) {
    }

    /**
     * @param businessDayFromStart posição do dia útil no mês (1 = primeiro). Um
     *                             crédito em sábado é atribuído ao dia útil
     *                             anterior — e {@code onBusinessDay} conta isso
     * @param businessDayFromEnd   quantos dias úteis ainda restam no mês (0 = último)
     */
    public record Occurrence(
            LocalDate date,
            int businessDayFromStart,
            int businessDayFromEnd,
            String weekday,
            boolean onBusinessDay,
            BigDecimal amount
    ) {
    }

    /**
     * Uma queda futura com faixa. {@code expected} é a aposta; {@code earliest}
     * e {@code latest} são os extremos observados projetados no mês — comprar
     * antes do {@code latest} é apostar que o dinheiro já caiu.
     *
     * @param adjusted o dia declarado caiu em fim de semana ou feriado e foi
     *                 movido para o dia útil vizinho
     * @param basis    de onde saiu ("mediana de 4 meses", "dia declarado por você")
     */
    public record Upcoming(
            String month,
            LocalDate expected,
            LocalDate earliest,
            LocalDate latest,
            boolean adjusted,
            String origin,
            String basis
    ) {
    }

    /**
     * "O vale cai N dias úteis antes do salário".
     *
     * @param linked verdadeiro quando há ao menos dois pares e a distância varia
     *               no máximo dois dias úteis
     */
    public record Relation(
            String to,
            Offset offsetBusinessDays,
            boolean linked,
            String label
    ) {
    }

    public record Offset(
            Integer median,
            Integer min,
            Integer max,
            int pairs
    ) {
    }

    public record Preference(
            String cadence,
            boolean weekendPreferred,
            String paymentMode,
            UUID cardAccountId,
            String fundingKind,
            OffsetDateTime updatedAt
    ) {
    }

    /**
     * O que o extrato de mercado sugere.
     *
     * @param purchasesPerMonth mediana de dias distintos com compra por mês
     * @param weekendShare      parcela do valor gasto em sábado e domingo (0..1)
     * @param daysAfterLanding  mediana de dias úteis entre a última entrada e a compra
     * @param weeklyDay         dia da semana mais frequente das compras
     */
    public record Inferred(
            String cadence,
            Double purchasesPerMonth,
            Double weekendShare,
            Integer daysAfterLanding,
            String weeklyDay,
            int monthsObserved,
            String confidence,
            String origin
    ) {
    }

    /**
     * @param cadenceOrigin INFORMED quando veio da preferência; MEASURED quando foi inferida
     * @param fundingSource o tipo da fonte que paga a compra (FOOD_VOUCHER, MEAL_VOUCHER, SALARY)
     * @param fundingDate   o {@code latest} da próxima queda dessa fonte — nunca antes disso
     * @param mustLastUntil até quando a compra precisa durar (a queda seguinte)
     * @param basis         o que a recomendação usou; o app compara para dizer
     *                      "recalculado depois que o vale de 28/08 entrou"
     */
    public record Advice(
            String cadence,
            String cadenceOrigin,
            String paymentMode,
            LocalDate bestDay,
            String bestDayWeekday,
            String fundingSource,
            LocalDate fundingDate,
            LocalDate mustLastUntil,
            Integer daysToCover,
            CardAdvice card,
            String weeklyDay,
            List<LocalDate> nextDates,
            String confidence,
            Explanation explanation,
            Basis basis
    ) {
    }

    /**
     * @param paidBySalaryOn a queda de salário que paga a fatura; nula quando
     *                       nenhuma cai entre a compra e o vencimento
     * @param warning        "a fatura vence antes do salário cair", quando for o caso
     */
    public record CardAdvice(
            UUID accountId,
            String name,
            Integer closingDay,
            LocalDate nextClosing,
            LocalDate invoiceDue,
            LocalDate paidBySalaryOn,
            String warning
    ) {
    }

    public record Explanation(
            String headline,
            List<String> lines
    ) {
    }

    public record Basis(
            int monthsObserved,
            LocalDate lastOccurrence
    ) {
    }
}
