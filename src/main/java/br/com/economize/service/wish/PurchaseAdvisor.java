package br.com.economize.service.wish;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A lógica PURA do "melhor dia de compra": nem repositório, nem Spring Data —
 * só aritmética de calendário e de lista, testável sem contexto nenhum.
 *
 * <p>Duas perguntas vivem aqui:
 * <ol>
 * <li><b>Como a pessoa compra hoje</b> ({@link #inferCadence}), quando ela não
 * declarou uma preferência — mensal ou semanal, fim de semana ou não, quantos
 * dias depois de o dinheiro cair.</li>
 * <li><b>Quando ela deveria comprar</b> ({@link #bestDayCash}/{@link
 * #cardAdvice}/{@link #weeklyNextDates}), dado o dinheiro que vai cair e,
 * quando o pagamento é no cartão, o ciclo de fatura dele.</li>
 * </ol>
 *
 * <p>A regra do cartão duplica de propósito a aritmética de fechamento/
 * vencimento de {@code CardInvoiceService} (que é package-private lá e mora em
 * outro pacote): são cinco linhas de calendário, e a alternativa — expor a
 * regra interna de outro serviço só para isto — acopla dois domínios que não
 * se conhecem.
 */
@Component
public class PurchaseAdvisor {

    /** Mediana de dias distintos de compra por mês até aqui inclusive é "mensal". */
    private static final double MONTHLY_CEILING = 1.5;

    /** Mediana a partir daqui é "toda semana". Entre os dois, o app supõe mensal. */
    private static final double WEEKLY_FLOOR = 3.5;

    public record PurchaseRecord(LocalDate date, BigDecimal amount) {
    }

    /**
     * @param purchasesPerMonth mediana de dias distintos com compra por mês
     * @param weekendShare      parcela do valor gasto em sábado/domingo (0..1)
     * @param daysAfterLanding  mediana de dias úteis entre a queda mais recente e a compra; nulo sem par nenhum
     * @param weeklyDay         dia da semana mais frequente das compras; nulo se a amostra for pequena demais
     * @param inferredLabel     verdadeiro sempre — quem grava a preferência explícita não passa por aqui
     */
    public record CadenceInference(String cadence, double purchasesPerMonth, double weekendShare,
                                    Integer daysAfterLanding, String weeklyDay, int monthsObserved,
                                    String confidence, boolean inferredLabel) {
    }

    public record CardRecommendation(LocalDate bestDay, LocalDate nextClosing, LocalDate invoiceDue,
                                      LocalDate paidBySalaryOn, String warning) {
    }

    /**
     * Cadência e hábito de compra a partir do extrato — usado quando a pessoa
     * não gravou uma preferência (V38 vazia). Sem nenhuma compra, a suposição
     * mais barata (mensal, confiança baixa) evita responder "não sei" para uma
     * pergunta que a tela sempre faz.
     */
    public CadenceInference inferCadence(List<PurchaseRecord> purchases, List<LocalDate> landings,
                                          int monthsObserved) {
        if (purchases.isEmpty()) {
            return new CadenceInference("MONTHLY", 0.0, 0.0, null, null, monthsObserved, "LOW", true);
        }

        Map<YearMonth, java.util.Set<LocalDate>> byMonth = new TreeMap<>();
        BigDecimal weekendAmount = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        Map<DayOfWeek, Integer> weekdayCount = new HashMap<>();
        for (PurchaseRecord p : purchases) {
            byMonth.computeIfAbsent(YearMonth.from(p.date()), m -> new java.util.HashSet<>()).add(p.date());
            BigDecimal amount = p.amount() != null ? p.amount().abs() : BigDecimal.ZERO;
            totalAmount = totalAmount.add(amount);
            DayOfWeek dow = p.date().getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                weekendAmount = weekendAmount.add(amount);
            }
            weekdayCount.merge(dow, 1, Integer::sum);
        }

        List<Integer> perMonth = byMonth.values().stream().map(java.util.Set::size).sorted().toList();
        double median = median(perMonth);

        double weekendShare = totalAmount.signum() > 0
                ? weekendAmount.divide(totalAmount, 4, java.math.RoundingMode.HALF_UP).doubleValue()
                : 0.0;

        Integer daysAfterLanding = medianDaysAfterLanding(purchases, landings);

        String weeklyDay = weekdayCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getKey().name())
                .orElse(null);

        String cadence;
        String confidence;
        if (median <= MONTHLY_CEILING) {
            cadence = "MONTHLY";
            confidence = monthsObserved >= 3 ? "MEDIUM" : "LOW";
        } else if (median >= WEEKLY_FLOOR) {
            cadence = "WEEKLY";
            confidence = monthsObserved >= 3 ? "MEDIUM" : "LOW";
        } else {
            // Nem claramente mensal nem claramente semanal: supor mensal é o
            // palpite mais barato, mas a confiança tem de dizer que é palpite
            cadence = "MONTHLY";
            confidence = "LOW";
        }

        return new CadenceInference(cadence, median, weekendShare, daysAfterLanding, weeklyDay,
                monthsObserved, confidence, true);
    }

    private Integer medianDaysAfterLanding(List<PurchaseRecord> purchases, List<LocalDate> landings) {
        if (landings.isEmpty()) return null;
        List<LocalDate> sortedLandings = landings.stream().sorted().toList();
        List<Long> gaps = new ArrayList<>();
        for (PurchaseRecord p : purchases) {
            LocalDate mostRecentLanding = null;
            for (LocalDate landing : sortedLandings) {
                if (!landing.isAfter(p.date())) {
                    mostRecentLanding = landing;
                } else {
                    break;
                }
            }
            if (mostRecentLanding != null) {
                gaps.add(br.com.economize.service.calendar.BrazilianBusinessDays
                        .businessDaysBetween(mostRecentLanding, p.date()));
            }
        }
        if (gaps.isEmpty()) return null;
        List<Long> sorted = gaps.stream().sorted().toList();
        return (int) Math.round(medianLong(sorted));
    }

    /** O sábado ou domingo mais próximo, no dia ou depois dele. */
    public LocalDate firstWeekendOnOrAfter(LocalDate from) {
        LocalDate cursor = from;
        while (cursor.getDayOfWeek() != DayOfWeek.SATURDAY && cursor.getDayOfWeek() != DayOfWeek.SUNDAY) {
            cursor = cursor.plusDays(1);
        }
        return cursor;
    }

    /**
     * O dia de compra quando o pagamento é em dinheiro da conta: nunca antes de
     * {@code fundingLatest} — comprar no dia esperado e o dinheiro cair só no
     * extremo tardio é a combinação que dá cheque sem fundo.
     */
    public LocalDate bestDayCash(LocalDate fundingLatest, boolean weekendPreferred) {
        return weekendPreferred ? firstWeekendOnOrAfter(fundingLatest) : fundingLatest;
    }

    /** O próximo fechamento de fatura a partir de hoje, inclusive. */
    public LocalDate nextClosing(int closingDay, LocalDate today) {
        YearMonth month = YearMonth.from(today);
        LocalDate closing = closingInMonth(month, closingDay);
        if (closing.isBefore(today)) {
            closing = closingInMonth(month.plusMonths(1), closingDay);
        }
        return closing;
    }

    private LocalDate closingInMonth(YearMonth month, int closingDay) {
        return month.atDay(Math.min(closingDay, month.lengthOfMonth()));
    }

    /** Vencimento da fatura que fechou em {@code closing}; nulo sem dia de vencimento conhecido. */
    public LocalDate dueDate(LocalDate closing, Integer dueDay) {
        if (dueDay == null) return null;
        YearMonth month = YearMonth.from(closing);
        LocalDate candidate = month.atDay(Math.min(dueDay, month.lengthOfMonth()));
        if (!candidate.isAfter(closing)) {
            YearMonth next = month.plusMonths(1);
            candidate = next.atDay(Math.min(dueDay, next.lengthOfMonth()));
        }
        return candidate;
    }

    /**
     * A recomendação inteira do cartão: fechar a compra DEPOIS do fechamento
     * iminente ({@code nextClosing}) garante que ela caia na fatura seguinte, e
     * é o vencimento DESSA fatura que precisa ser pago por um salário.
     *
     * @param salaryDates datas (previstas ou já medidas) de queda do salário,
     *                     em qualquer ordem
     */
    public CardRecommendation cardAdvice(int closingDay, Integer dueDay, LocalDate today,
                                          boolean weekendPreferred, List<LocalDate> salaryDates) {
        LocalDate closing = nextClosing(closingDay, today);
        LocalDate rawBestDay = closing.plusDays(1);
        LocalDate bestDay = weekendPreferred ? firstWeekendOnOrAfter(rawBestDay) : rawBestDay;

        LocalDate followingClosing = nextClosing(closingDay, closing.plusDays(1));
        LocalDate invoiceDue = dueDate(followingClosing, dueDay);

        LocalDate paidBySalaryOn = null;
        String warning = null;
        if (invoiceDue != null) {
            paidBySalaryOn = salaryDates.stream()
                    .filter(d -> d.isAfter(closing) && !d.isAfter(invoiceDue))
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            if (paidBySalaryOn == null) {
                warning = "A fatura vence antes do salário cair.";
            }
        }
        return new CardRecommendation(bestDay, closing, invoiceDue, paidBySalaryOn, warning);
    }

    /** As próximas {@code count} datas do dia da semana preferido, a partir de (e incluindo) {@code from}. */
    public List<LocalDate> weeklyNextDates(DayOfWeek preferredDay, LocalDate from, int count) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = from;
        while (cursor.getDayOfWeek() != preferredDay) {
            cursor = cursor.plusDays(1);
        }
        for (int i = 0; i < count; i++) {
            dates.add(cursor);
            cursor = cursor.plusDays(7);
        }
        return dates;
    }

    private static double median(List<Integer> sorted) {
        if (sorted.isEmpty()) return 0.0;
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static double medianLong(List<Long> sorted) {
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
