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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Previsão de saldo (EC-096): projeta as séries recorrentes ativas período a
 * período a partir do período corrente, com o acumulado partindo do baseline
 * informado pelo app — o sistema NÃO tem saldo corrente consolidado, então
 * inventar um aqui seria mentir; quem sabe o saldo é o usuário.
 *
 * <p>O período é o "mês do usuário" (EC-116). Sem recorte na requisição ele é o
 * mês do calendário, como sempre foi; com o recorte ({@link AnalysisWindow}, a
 * MESMA janela de {@code /analytics/monthly}) o primeiro período é a janela
 * pedida e os seguintes são os ciclos que a continuam. Todos devolvem
 * {@code start}/{@code end} explícitos: a Home mostrava "12/07 → 11/08" num card
 * e "saldo previsto negativo em set 2026" na mesma rolagem, dois significados de
 * "mês" sem nada distinguindo.
 *
 * <p>Regras de projeção:
 * <ul>
 * <li>Só séries ativas, não descartadas, de fluxo EXPENSE/INCOME. INTERNAL
 * nunca entra (dinheiro do titular circulando não é gasto nem renda) e
 * IRREGULAR fica de fora: sem ciclo estimável, qualquer valor mensal seria
 * chute — a série segue visível na listagem, só não vira previsão.</li>
 * <li>WEEKLY entra pelo equivalente do período. No mês do calendário são as
 * 52/12 ≈ 4,33 ocorrências de sempre: {@code expected × 52 ÷ 12}. Em qualquer
 * outro recorte o fator vira o número real de semanas do período,
 * {@code expected × dias ÷ 7} — aplicar 4,33 a um ciclo de 28 dias inflaria em
 * ~8% um período que só tem 4 semanas, e a um recorte de 32 dias faltariam ~5%.
 * Os dois caminhos preservam o mesmo invariante que justificou o 4,33: doze
 * períodos contíguos somam as ~52 semanas do ano.</li>
 * <li>Período corrente projeta só o que falta: série com ocorrência real
 * conciliada DENTRO do recorte aparece {@code settled=true} e fora das somas;
 * WEEKLY liquida gradualmente — cada ocorrência conciliada abate um
 * {@code expected} do equivalente do período.</li>
 * <li>startsAt/endsAt delimitam a vigência: a cobrança datada não pode preceder
 * startsAt nem suceder endsAt; WEEKLY, sem dia único, entra pelo período cheio
 * que encosta na vigência, sem rateio.</li>
 * <li>As datas de vencimento saem da MESMA corrente do {@code nextDueDate} da
 * listagem ({@link RecurringSeriesService#dueAfter}), não da âncora do
 * calendário: a cobrança que desliza para antes da âncora (dia 30 de janeiro
 * para uma âncora dia 2) pertence ao ciclo seguinte, e contar "todo mês tem a
 * âncora" a somava duas vezes — uma como ocorrência real, outra como projeção
 * do mês que ela já pagou. Cada vencimento é atribuído ao período que o CONTÉM,
 * o que resolve sozinho a cobrança que cai na fronteira do ciclo.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RecurrenceForecastService {

    static final int DEFAULT_MONTHS = 3;
    static final int MAX_MONTHS = 12;
    // ~50 anos de ciclos mensais: teto de segurança da corrente de vencimentos
    private static final int MAX_DUE_STEPS = 600;

    // 52 semanas / 12 meses — ver javadoc da classe
    private static final BigDecimal WEEKS_PER_YEAR = BigDecimal.valueOf(52);
    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);
    private static final BigDecimal DAYS_PER_WEEK = BigDecimal.valueOf(7);

    private final RecurringSeriesRepository seriesRepository;
    private final RecurringSeriesLinkRepository linkRepository;
    private final UserRepository userRepository;

    /**
     * {@code window} nulo = projeção por mês do calendário a partir de hoje, o
     * comportamento de antes do EC-116 (é o que o APK publicado recebe, já que
     * ele só manda {@code months} e {@code startingBalance}).
     *
     * <p>O recorte chega por PARÂMETRO, na janela já resolvida pelo controller,
     * porque a âncora do ciclo vive como preferência do aparelho — o servidor não
     * tem onde consultá-la, e inventar uma coluna para ela transformaria uma
     * decisão de tela em migration.
     *
     * <p>O "hoje" é lido em UTC, o mesmo fuso em que o extrato data o lançamento
     * e em que a contagem de ocorrências recorta o período. Ler o dia no fuso do
     * servidor e as ocorrências em UTC deixaria a virada do ciclo depender de
     * onde a API está hospedada.
     */
    public ForecastResponse forecast(String email, Integer months, BigDecimal startingBalance,
                                     AnalysisWindow window) {
        return forecast(email, months, startingBalance, window, LocalDate.now(ZoneOffset.UTC));
    }

    ForecastResponse forecast(String email, Integer monthsParam, BigDecimal startingBalance,
                              AnalysisWindow requestedWindow, LocalDate today) {
        int months = monthsParam != null ? monthsParam : DEFAULT_MONTHS;
        if (months < 1 || months > MAX_MONTHS) {
            throw new IllegalArgumentException("months deve estar entre 1 e " + MAX_MONTHS);
        }
        // sem recorte a previsão abre no mês do calendário corrente, exatamente
        // como antes do EC-116
        AnalysisWindow window = requestedWindow != null
                ? requestedWindow
                : AnalysisWindow.ofMonth(YearMonth.from(today));
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));

        List<RecurringSeries> projectable = seriesRepository.findAllByUserId(user.getId()).stream()
                .filter(series -> series.isActive() && !series.isDismissed())
                .filter(series -> series.getFlow() == RecurringSeries.Flow.EXPENSE
                        || series.getFlow() == RecurringSeries.Flow.INCOME)
                .filter(series -> series.getCadence() != RecurringSeries.Cadence.IRREGULAR)
                // sem valor esperado não há o que somar (não acontece nas séries
                // do motor nem nas agendadas; proteção contra dado legado)
                .filter(series -> series.getExpectedAmount() != null)
                .toList();

        int anchorDay = ForecastPeriod.anchorOf(window.start(), window.end());
        List<ForecastPeriod> periods = periods(window, anchorDay, months);
        Map<UUID, Long> settledInCurrentPeriod = loadOccurrencesInPeriod(projectable, periods.get(0));
        Map<UUID, Map<Integer, LocalDate>> dueDates = new HashMap<>();
        for (RecurringSeries series : projectable) {
            dueDates.put(series.getId(), dueDatesByPeriod(series, periods));
        }

        List<ForecastMonthResponse> periodResponses = new ArrayList<>();
        BigDecimal cumulative = startingBalance != null ? startingBalance : BigDecimal.ZERO;
        for (int index = 0; index < periods.size(); index++) {
            ForecastPeriod period = periods.get(index);
            boolean isCurrent = index == 0;

            List<ForecastItemResponse> items = new ArrayList<>();
            BigDecimal income = BigDecimal.ZERO;
            BigDecimal expense = BigDecimal.ZERO;
            for (RecurringSeries series : projectable) {
                long settledCount = isCurrent
                        ? settledInCurrentPeriod.getOrDefault(series.getId(), 0L)
                        : 0L;
                ForecastItemResponse item = project(series, period, settledCount,
                        dueDates.get(series.getId()).get(index));
                if (item == null) continue;
                items.add(item);
                if (item.settled()) continue;
                if (series.getFlow() == RecurringSeries.Flow.INCOME) {
                    income = income.add(item.amount());
                } else {
                    expense = expense.add(item.amount());
                }
            }
            // ordenar pela DATA e não pelo dia: dentro de um ciclo ancorado o dia
            // 5 (do mês seguinte) vem DEPOIS do dia 20. No mês do calendário as
            // duas ordens coincidem, então nada muda para quem não manda recorte
            items.sort(Comparator
                    .comparing(ForecastItemResponse::dueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ForecastItemResponse::displayName,
                            Comparator.nullsLast(Comparator.naturalOrder())));

            BigDecimal net = income.subtract(expense);
            cumulative = cumulative.add(net);
            periodResponses.add(new ForecastMonthResponse(period.month().toString(),
                    period.start(), period.end(),
                    money(income), money(expense), money(net), money(cumulative), items));
        }
        return new ForecastResponse(startingBalance, anchorDay, periodResponses);
    }

    /**
     * Os períodos da projeção. O primeiro é o recorte pedido, tal como veio — em
     * modo ciclo ele normalmente abriu ANTES de hoje, e é exatamente por isso que
     * o período corrente projeta só o que falta. Os seguintes encadeiam ciclos
     * contíguos a partir dele, pela âncora que a própria janela carrega.
     */
    private List<ForecastPeriod> periods(AnalysisWindow window, int anchorDay, int months) {
        List<ForecastPeriod> periods = new ArrayList<>(months);
        periods.add(ForecastPeriod.first(window.start(), window.end()));
        for (int offset = 1; offset < months; offset++) {
            periods.add(ForecastPeriod.next(periods.get(offset - 1), anchorDay));
        }
        return periods;
    }

    /**
     * Projeta uma série num período; null quando ela não vence nele (a corrente
     * de vencimentos não passa por ali, ou o período está fora da vigência).
     * {@code settledCount} só chega > 0 para o período corrente, e {@code due} é
     * a data que a corrente reservou para este período (null quando não há).
     */
    private ForecastItemResponse project(RecurringSeries series, ForecastPeriod period,
                                         long settledCount, LocalDate due) {
        if (series.getCadence() == RecurringSeries.Cadence.WEEKLY) {
            if (!overlapsValidity(series, period)) return null;
            BigDecimal periodEquivalent = weeklyEquivalent(series.getExpectedAmount(), period);
            if (settledCount > 0) {
                BigDecimal consumed = series.getExpectedAmount()
                        .multiply(BigDecimal.valueOf(settledCount));
                BigDecimal remaining = periodEquivalent.subtract(consumed);
                if (remaining.signum() <= 0) {
                    return item(series, null, periodEquivalent, true);
                }
                return item(series, null, money(remaining), false);
            }
            return item(series, null, periodEquivalent, false);
        }

        // o que já caiu no período corrente é fato, não projeção: aparece
        // liquidado mesmo que a corrente não previsse cobrança neste período
        if (settledCount > 0) {
            LocalDate reference = due != null ? due : anchorDateWithin(series, period);
            return item(series, reference, money(series.getExpectedAmount()), true);
        }
        if (due == null) return null;
        return item(series, due, money(series.getExpectedAmount()), false);
    }

    /**
     * Índice do período em que a série vence, com a data de cada vencimento. A
     * corrente parte do {@code nextDueDate} exibido na listagem e avança um
     * ciclo por vez pela mesma regra de âncora — é o que mantém as duas telas
     * contando as mesmas cobranças, e o que dá fase coerente ao QUARTERLY (o
     * trimestre conta a partir da ocorrência, não do calendário).
     */
    private Map<Integer, LocalDate> dueDatesByPeriod(RecurringSeries series,
                                                     List<ForecastPeriod> periods) {
        if (series.getCadence() == RecurringSeries.Cadence.WEEKLY) return Map.of();
        LocalDate first = periods.get(0).start();
        LocalDate last = periods.get(periods.size() - 1).end();
        Map<Integer, LocalDate> byPeriod = new HashMap<>();
        LocalDate due = seedDue(series, periods.get(0));
        // a corrente pode começar anos antes da janela (agendamento antigo que
        // nunca conciliou): o teto só impede laço infinito em dado corrompido
        for (int step = 0; due != null && step < MAX_DUE_STEPS; step++) {
            if (due.isAfter(last)) break;
            if (!due.isBefore(first) && withinValidity(series, due)) {
                int index = indexOfPeriodContaining(periods, due);
                if (index >= 0) byPeriod.putIfAbsent(index, due);
            }
            due = RecurringSeriesService.dueAfter(series, due);
        }
        return byPeriod;
    }

    // os períodos são contíguos e ordenados, e são no máximo 12: varrer é mais
    // barato (e mais óbvio) do que indexar
    private int indexOfPeriodContaining(List<ForecastPeriod> periods, LocalDate date) {
        for (int index = 0; index < periods.size(); index++) {
            if (periods.get(index).contains(date)) return index;
        }
        return -1;
    }

    private LocalDate seedDue(RecurringSeries series, ForecastPeriod first) {
        LocalDate due = RecurringSeriesService.nextDueDate(series);
        if (due != null) return due;
        // sem ocorrência real e sem vigência não há fase de onde partir (só
        // acontece com dado legado): a corrente começa na âncora do mês em que o
        // 1º período abre, e o laço a empurra para a frente se cair cedo demais
        if (series.getLastSeenAt() == null && series.getStartsAt() == null) {
            return occurrenceDate(series, YearMonth.from(first.start()));
        }
        return null;
    }

    /**
     * Vigência para cobrança DATADA: a cobrança não pode preceder startsAt nem
     * suceder endsAt. A comparação é por data, não por mês, porque num ciclo
     * ancorado o mês do vencimento não identifica o período.
     */
    private boolean withinValidity(RecurringSeries series, LocalDate occurrence) {
        LocalDate startsAt = series.getStartsAt();
        if (startsAt != null && occurrence.isBefore(startsAt)) return false;
        LocalDate endsAt = series.getEndsAt();
        return endsAt == null || !occurrence.isAfter(endsAt);
    }

    /**
     * Vigência para WEEKLY, que não tem dia único: basta o período ENCOSTAR na
     * vigência para a série entrar inteira, sem rateio — é o comportamento de
     * sempre, agora escrito em datas em vez de meses.
     */
    private boolean overlapsValidity(RecurringSeries series, ForecastPeriod period) {
        LocalDate startsAt = series.getStartsAt();
        if (startsAt != null && period.end().isBefore(startsAt)) return false;
        LocalDate endsAt = series.getEndsAt();
        return endsAt == null || !period.start().isAfter(endsAt);
    }

    // dia estimado da cobrança DENTRO do período (âncora ajustada ao tamanho do
    // mês); null para WEEKLY, sem dia único, e para o ciclo curto que não contém
    // nenhum dia igual à âncora da série
    private LocalDate anchorDateWithin(RecurringSeries series, ForecastPeriod period) {
        if (series.getCadence() == RecurringSeries.Cadence.WEEKLY || series.getAnchorDay() == null) {
            return null;
        }
        return period.dayWithin(series.getAnchorDay());
    }

    private LocalDate occurrenceDate(RecurringSeries series, YearMonth month) {
        if (series.getCadence() == RecurringSeries.Cadence.WEEKLY || series.getAnchorDay() == null) {
            return null;
        }
        return ForecastPeriod.anchoredDay(month, series.getAnchorDay());
    }

    private Map<UUID, Long> loadOccurrencesInPeriod(List<RecurringSeries> series, ForecastPeriod period) {
        if (series.isEmpty()) return Map.of();
        List<UUID> ids = series.stream().map(RecurringSeries::getId).toList();
        OffsetDateTime from = period.start().atStartOfDay().atOffset(ZoneOffset.UTC);
        // exclusivo: o dia seguinte ao fim, porque o fim do período é inclusivo
        OffsetDateTime to = period.end().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        Map<UUID, Long> counts = new HashMap<>();
        for (RecurringSeriesLinkRepository.SeriesOccurrences row
                : linkRepository.countBySeriesIdInPeriod(ids, from, to)) {
            counts.put(row.getSeriesId(), row.getOccurrences());
        }
        return counts;
    }

    private ForecastItemResponse item(RecurringSeries series, LocalDate occurrence,
                                      BigDecimal amount, boolean settled) {
        return new ForecastItemResponse(
                series.getId(),
                series.getDisplayName() != null ? series.getDisplayName() : series.getMerchantKey(),
                series.getFlow(),
                occurrence != null ? occurrence.getDayOfMonth() : null,
                occurrence,
                amount,
                series.getSource(),
                settled);
    }

    /**
     * Quantas cobranças semanais cabem no período. Ver o javadoc da classe: mês
     * do calendário mantém o 52/12 histórico; ciclo ancorado usa os dias reais
     * do recorte, que é a única medida honesta quando o "mês" tem 28 ou 31 dias
     * conforme onde a âncora cai.
     */
    private BigDecimal weeklyEquivalent(BigDecimal expectedAmount, ForecastPeriod period) {
        if (period.isCalendarMonth()) {
            return expectedAmount.multiply(WEEKS_PER_YEAR)
                    .divide(MONTHS_PER_YEAR, 2, RoundingMode.HALF_UP);
        }
        return expectedAmount.multiply(BigDecimal.valueOf(period.lengthInDays()))
                .divide(DAYS_PER_WEEK, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
