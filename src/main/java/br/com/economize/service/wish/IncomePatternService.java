package br.com.economize.service.wish;

import br.com.economize.dto.analytics.IncomePatternResponse;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.IncomeSource;
import br.com.economize.model.PurchasePreference;
import br.com.economize.model.RecurringSeries;
import br.com.economize.model.RecurringSeriesLink;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.ConnectorAccountRepository;
import br.com.economize.repository.IncomeSourceRepository;
import br.com.economize.repository.PurchasePreferenceRepository;
import br.com.economize.repository.RecurringSeriesLinkRepository;
import br.com.economize.repository.RecurringSeriesRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.calendar.BrazilianBusinessDays;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * O padrão de entradas em DIA ÚTIL (não em dia do calendário) e o melhor dia
 * para as compras derivados dele — plano v1 do "melhor dia de compra".
 *
 * <p><b>Tudo aqui é DERIVADO a cada leitura.</b> Nada é gravado em
 * {@code income_sources} nem em {@code recurring_series} (EC-205: o app
 * propõe, não move) — a única escrita do domínio é a preferência de compra
 * (V38, {@link PurchasePreference}), que é o que o extrato NUNCA vai contar
 * sozinho (mensal ou semanal, fim de semana ou não, dinheiro ou cartão).
 *
 * <p>Ordem das fontes: {@code IncomeSource} ativas são a fonte primária — com
 * série vinculada (MEASURED, datas reais) ou só com {@code anchorDay}
 * declarado (INFORMED, sem histórico). Sem nenhuma fonte cadastrada, cai nas
 * séries {@code RecurringSeries} de fluxo INCOME (mesma régua de
 * {@link IncomeSourceService#overview}), como sugestão não confirmada.
 */
@Service
@RequiredArgsConstructor
public class IncomePatternService {

    /** Duas ocorrências dão um intervalo só; três é o mínimo para falar em padrão. */
    static final int MIN_OCCURRENCES = 3;

    /** Histórico de crédito considerado — mais que isso é ruído de conta antiga. */
    private static final int HISTORY_MONTHS = 18;

    /** Quantas ocorrências recentes entram na mediana do valor esperado. */
    private static final int RECENT_AMOUNTS = 6;

    /** Compra abaixo disso é padaria/conveniência, não "a compra do mês" (ver Materiality). */
    private static final BigDecimal GROCERY_FLOOR = new BigDecimal("80.00");

    private static final int GROCERY_WINDOW_MONTHS = 6;

    // Mesmo vocabulário do systemKey FOOD_GROCERIES em RuleBasedCategorizationService
    // (privado lá): duplicado aqui só como reforço quando a transação não tem
    // categoria — o filtro principal é o categoryId.
    private static final List<String> GROCERY_HINTS = List.of(
            "supermercado", "mercadinho", "minimercado", "mercearia", "adega", "hortifruti",
            "sacolao", "sacolão", "atacadao", "atacadão", "assai", "assaí", "carrefour",
            "roldao", "roldão", "pao de acucar", "pão de açúcar", "big bompreco", "extra super");

    private static final Set<IncomeSource.Kind> VOUCHER_KINDS =
            Set.of(IncomeSource.Kind.MEAL_VOUCHER, IncomeSource.Kind.FOOD_VOUCHER, IncomeSource.Kind.ADVANCE);

    private static final String[] MESES = {
            "janeiro", "fevereiro", "março", "abril", "maio", "junho",
            "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"};

    private static final Map<DayOfWeek, String> WEEKDAY_PT = Map.of(
            DayOfWeek.MONDAY, "seg", DayOfWeek.TUESDAY, "ter", DayOfWeek.WEDNESDAY, "qua",
            DayOfWeek.THURSDAY, "qui", DayOfWeek.FRIDAY, "sex", DayOfWeek.SATURDAY, "sáb",
            DayOfWeek.SUNDAY, "dom");

    private final IncomeSourceRepository incomeSourceRepository;
    private final RecurringSeriesRepository recurringSeriesRepository;
    private final RecurringSeriesLinkRepository linkRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final ConnectorAccountRepository connectorAccountRepository;
    private final PurchasePreferenceRepository purchasePreferenceRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final PurchaseAdvisor advisor;

    /** Datas de uma ocorrência já resolvida (mês já reduzido ao maior crédito). */
    private record Occ(LocalDate date, BigDecimal amount) {
    }

    /** Os dados brutos de uma fonte antes de virar {@link IncomePatternResponse.SourcePattern}. */
    private record SourceData(UUID incomeSourceId, UUID seriesId, IncomeSource.Kind kind, String name,
                               boolean confirmed, String origin, Short anchorDay, BigDecimal declaredAmount,
                               List<Occ> occurrences) {
    }

    private record Candidate(String rule, List<Integer> values) {
        int spread() {
            return values.isEmpty() ? 0 : max() - min();
        }

        int min() {
            return values.stream().mapToInt(Integer::intValue).min().orElse(0);
        }

        int max() {
            return values.stream().mapToInt(Integer::intValue).max().orElse(0);
        }

        int median() {
            return medianOf(values);
        }

        double mad() {
            int med = median();
            List<Integer> deviations = values.stream().map(v -> Math.abs(v - med)).toList();
            return medianOf(deviations);
        }
    }

    @Transactional(readOnly = true)
    public IncomePatternResponse analyze(String email) {
        return analyze(email, LocalDate.now(ZoneOffset.UTC));
    }

    /** Sobrecarga com "hoje" explícito — é o que torna o cálculo testável. */
    @Transactional(readOnly = true)
    IncomePatternResponse analyze(String email, LocalDate today) {
        User user = requireUser(email);
        UUID userId = user.getId();

        List<SourceData> sourceDataList = collectSources(userId, today);
        // Histórico MEDIDO — para parear o VR com o salário já caído (relação §2)
        List<LocalDate> historicalSalaryDates = sourceDataList.stream()
                .filter(sd -> sd.kind() == IncomeSource.Kind.SALARY)
                .flatMap(sd -> sd.occurrences().stream())
                .map(Occ::date)
                .distinct()
                .sorted()
                .toList();

        List<IncomePatternResponse.SourcePattern> sources = new ArrayList<>();
        for (SourceData sd : sourceDataList) {
            IncomePatternResponse.Relation relation = sd.kind() != IncomeSource.Kind.SALARY
                    ? buildRelation(sd, historicalSalaryDates)
                    : null;
            sources.add(buildSourcePattern(sd, today, relation));
        }

        // Datas FUTURAS de salário — é contra elas que o cartão compara o
        // vencimento da fatura (o histórico já caiu, não paga fatura nenhuma)
        List<LocalDate> futureSalaryDates = sources.stream()
                .filter(sp -> sp.kind().equals("SALARY"))
                .flatMap(sp -> sp.upcoming().stream())
                .map(IncomePatternResponse.Upcoming::expected)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        PurchasePreference stored = purchasePreferenceRepository.findById(userId).orElse(null);
        IncomePatternResponse.Preference preferenceDto = stored == null ? null : toPreferenceDto(stored);

        List<PurchaseAdvisor.PurchaseRecord> purchases = groceryPurchases(userId, today);
        List<LocalDate> landings = sourceDataList.stream()
                .flatMap(sd -> sd.occurrences().stream()).map(Occ::date).distinct().toList();
        int purchaseMonths = distinctMonths(purchases);
        PurchaseAdvisor.CadenceInference inference = advisor.inferCadence(purchases, landings, purchaseMonths);
        IncomePatternResponse.Inferred inferredDto = new IncomePatternResponse.Inferred(
                inference.cadence(), inference.purchasesPerMonth(), inference.weekendShare(),
                inference.daysAfterLanding(), inference.weeklyDay(), inference.monthsObserved(),
                inference.confidence(), "MEASURED");

        if (sourceDataList.isEmpty()) {
            return new IncomePatternResponse("NO_INCOME",
                    "Cadastre uma fonte de renda para eu aprender o padrão de quando ela cai.",
                    today, sources, preferenceDto, inferredDto, null);
        }

        boolean anyUsable = sources.stream().anyMatch(sp -> !sp.upcoming().isEmpty());
        if (!anyUsable) {
            int maxObserved = sourceDataList.stream().mapToInt(sd -> sd.occurrences().size()).max().orElse(0);
            String plural = maxObserved == 1 ? "" : "s";
            return new IncomePatternResponse("INSUFFICIENT_HISTORY",
                    "Vi só " + maxObserved + " pagamento" + plural
                            + "; a partir de 3 meses eu digo o padrão.",
                    today, sources, preferenceDto, inferredDto, null);
        }

        AdviceResult built = buildAdvice(sources, stored, inference, today, userId, futureSalaryDates);
        if (built.cardCycleUnknown()) {
            return new IncomePatternResponse("CARD_CYCLE_UNKNOWN",
                    "Não sei quando seu cartão fecha; conecte o banco de novo ou informe o dia.",
                    today, sources, preferenceDto, inferredDto, null);
        }

        return new IncomePatternResponse("READY", null, today, sources, preferenceDto, inferredDto,
                built.advice());
    }

    // ---------------------------------------------------------------- fontes

    private List<SourceData> collectSources(UUID userId, LocalDate today) {
        List<IncomeSource> declared = incomeSourceRepository.findAllByUserIdAndActiveTrue(userId);
        List<SourceData> result = new ArrayList<>();
        if (!declared.isEmpty()) {
            for (IncomeSource source : declared) {
                List<Occ> occurrences = source.getSeriesId() != null
                        ? occurrencesForSeries(userId, List.of(source.getSeriesId()), today)
                        : List.of();
                String origin = source.getSeriesId() != null ? "MEASURED" : "INFORMED";
                result.add(new SourceData(source.getId(), source.getSeriesId(), source.getKind(),
                        source.getName(), source.isConfirmed(), origin, source.getAnchorDay(),
                        source.getExpectedAmount(), occurrences));
            }
            return result;
        }

        // Sem fonte declarada nenhuma: cai nas séries INCOME que o extrato já
        // provou, mesmo filtro de IncomeSourceService.suggestions — descartada
        // ou irregular não vira sugestão de padrão
        for (RecurringSeries series : recurringSeriesRepository.findAllByUserId(userId)) {
            if (series.getFlow() != RecurringSeries.Flow.INCOME) continue;
            if (!series.isActive() || series.isDismissed()) continue;
            if (series.getCadence() == RecurringSeries.Cadence.IRREGULAR) continue;

            List<Occ> occurrences = occurrencesForSeries(userId, List.of(series.getId()), today);
            String label = series.getDisplayName() != null ? series.getDisplayName() : series.getMerchantKey();
            IncomeSource.Kind kind = IncomeSourceService.guessKind(label + " " + series.getMerchantKey());
            result.add(new SourceData(null, series.getId(), kind, label, false, "MEASURED",
                    null, series.getExpectedAmount(), occurrences));
        }
        return result;
    }

    /**
     * Uma ocorrência por mês (o crédito de MAIOR valor — descarta recarga/
     * adiantamento extra) e dedupe por data (duas origens do mesmo crédito no
     * mesmo dia contam uma vez).
     */
    private List<Occ> occurrencesForSeries(UUID userId, List<UUID> seriesIds, LocalDate today) {
        List<RecurringSeriesLink> links = linkRepository.findAllBySeriesIdIn(seriesIds);
        if (links.isEmpty()) return List.of();
        List<UUID> txIds = links.stream().map(RecurringSeriesLink::getBankTransactionId).toList();
        List<BankTransaction> transactions = bankTransactionRepository.findAllByUserIdAndIdIn(userId, txIds);

        LocalDate cutoff = today.minusMonths(HISTORY_MONTHS);
        Map<LocalDate, BigDecimal> byDate = new TreeMap<>();
        for (BankTransaction tx : transactions) {
            if (tx.isIgnored()) continue;
            if (!"CREDIT".equalsIgnoreCase(tx.getType())) continue;
            if (tx.getAmount() == null) continue;
            LocalDate day = utcDay(tx.getDate());
            if (day.isBefore(cutoff)) continue;
            byDate.merge(day, tx.getAmount().abs(), BigDecimal::max);
        }

        Map<YearMonth, Occ> byMonth = new TreeMap<>();
        for (Map.Entry<LocalDate, BigDecimal> entry : byDate.entrySet()) {
            YearMonth month = YearMonth.from(entry.getKey());
            Occ candidate = new Occ(entry.getKey(), entry.getValue());
            byMonth.merge(month, candidate,
                    (a, b) -> b.amount().compareTo(a.amount()) > 0 ? b : a);
        }
        return byMonth.values().stream().sorted(Comparator.comparing(Occ::date)).toList();
    }

    // ------------------------------------------------------------- padrão

    private IncomePatternResponse.SourcePattern buildSourcePattern(SourceData sd, LocalDate today,
                                                                    IncomePatternResponse.Relation relation) {
        List<IncomePatternResponse.Occurrence> occurrenceDtos =
                sd.occurrences().stream().map(this::toOccurrenceDto).toList();

        IncomePatternResponse.Pattern pattern;
        List<IncomePatternResponse.Upcoming> upcoming;

        if (sd.occurrences().size() >= MIN_OCCURRENCES) {
            pattern = computeMeasuredPattern(sd);
            upcoming = computeUpcoming(pattern.rule(), pattern.median(), pattern.min(), pattern.max(),
                    "MEASURED", "mediana de " + sd.occurrences().size() + " meses observados", today);
        } else if (sd.occurrences().isEmpty() && sd.anchorDay() != null) {
            // INFORMED: sem uma transação sequer, só o dia declarado
            int day = sd.anchorDay();
            pattern = new IncomePatternResponse.Pattern("CALENDAR_DAY", day, day, day, 0,
                    null, null, sd.declaredAmount(), null, "dia " + day + " (informado por você)");
            upcoming = computeUpcoming("CALENDAR_DAY", day, day, day, "INFORMED",
                    "dia declarado por você", today);
        } else {
            // Medido, mas ainda sem 3 meses — não há padrão para apostar
            pattern = null;
            upcoming = List.of();
        }

        return new IncomePatternResponse.SourcePattern(sd.incomeSourceId(), sd.seriesId(),
                sd.kind().name(), sd.name(), sd.confirmed(), sd.origin(), pattern, occurrenceDtos,
                upcoming, relation);
    }

    private IncomePatternResponse.Occurrence toOccurrenceDto(Occ occ) {
        boolean onBusinessDay = BrazilianBusinessDays.isBusinessDay(occ.date());
        LocalDate attributed = onBusinessDay ? occ.date() : BrazilianBusinessDays.previousBusinessDay(occ.date());
        int fromStart = BrazilianBusinessDays.businessDayOfMonth(attributed);
        int fromEnd = BrazilianBusinessDays.businessDaysUntilMonthEnd(attributed);
        return new IncomePatternResponse.Occurrence(occ.date(), fromStart, fromEnd,
                occ.date().getDayOfWeek().name(), onBusinessDay, occ.amount());
    }

    private IncomePatternResponse.Pattern computeMeasuredPattern(SourceData sd) {
        List<Occ> occurrences = sd.occurrences();
        List<Integer> fromStart = new ArrayList<>();
        List<Integer> fromEnd = new ArrayList<>();
        List<Integer> calendarDay = new ArrayList<>();
        for (Occ occ : occurrences) {
            LocalDate attributed = BrazilianBusinessDays.isBusinessDay(occ.date())
                    ? occ.date() : BrazilianBusinessDays.previousBusinessDay(occ.date());
            fromStart.add(BrazilianBusinessDays.businessDayOfMonth(attributed));
            fromEnd.add(BrazilianBusinessDays.businessDaysUntilMonthEnd(attributed));
            calendarDay.add(occ.date().getDayOfMonth());
        }

        Candidate chosen = pickRule(new Candidate("BUSINESS_DAY_FROM_START", fromStart),
                new Candidate("BUSINESS_DAY_FROM_END", fromEnd),
                new Candidate("CALENDAR_DAY", calendarDay));

        int n = occurrences.size();
        int spread = chosen.spread();
        String confidence;
        if (n >= 6 && spread <= 1) confidence = "HIGH";
        else if (n >= 3 && spread <= 3) confidence = "MEDIUM";
        else confidence = "LOW";

        List<Occ> recent = occurrences.stream()
                .sorted(Comparator.comparing(Occ::date).reversed())
                .limit(RECENT_AMOUNTS)
                .toList();
        BigDecimal expectedAmount = medianAmount(recent);

        String label = labelFor(chosen.rule(), chosen.median());

        return new IncomePatternResponse.Pattern(chosen.rule(), chosen.median(), chosen.min(), chosen.max(),
                n, occurrences.get(0).date(), occurrences.get(occurrences.size() - 1).date(),
                expectedAmount, confidence, label);
    }

    /** Menor dispersão vence; empate por menor MAD; empate final por regra fixa (ver §1 do plano). */
    private Candidate pickRule(Candidate fromStart, Candidate fromEnd, Candidate calendarDay) {
        List<Candidate> all = List.of(fromStart, fromEnd, calendarDay);
        int minSpread = all.stream().mapToInt(Candidate::spread).min().orElse(0);
        List<Candidate> byspread = all.stream().filter(c -> c.spread() == minSpread).toList();
        if (byspread.size() == 1) return byspread.get(0);

        double minMad = byspread.stream().mapToDouble(Candidate::mad).min().orElse(0);
        List<Candidate> byMad = byspread.stream().filter(c -> c.mad() == minMad).toList();
        if (byMad.size() == 1) return byMad.get(0);

        return byMad.stream().filter(c -> c.rule().equals("CALENDAR_DAY") && c.spread() <= 1)
                .findFirst()
                .or(() -> byMad.stream().filter(c -> c.rule().equals("BUSINESS_DAY_FROM_START")).findFirst())
                .orElse(byMad.get(0));
    }

    private String labelFor(String rule, int median) {
        return switch (rule) {
            case "BUSINESS_DAY_FROM_START" -> "por volta do " + ordinal(median) + " dia útil";
            case "BUSINESS_DAY_FROM_END" -> median == 0
                    ? "no último dia útil do mês" : "nos últimos dias úteis do mês";
            default -> "por volta do dia " + median;
        };
    }

    private List<IncomePatternResponse.Upcoming> computeUpcoming(String rule, int median, int min, int max,
                                                                  String origin, String basis, LocalDate today) {
        List<IncomePatternResponse.Upcoming> upcoming = new ArrayList<>();
        YearMonth month = YearMonth.from(today);
        // Se a queda deste mês já passou, começa do mês seguinte
        LocalDate expectedThisMonth = resolve(rule, month, median);
        if (expectedThisMonth != null && expectedThisMonth.isBefore(today)) {
            month = month.plusMonths(1);
        }
        for (int i = 0; i < 3; i++) {
            YearMonth target = month.plusMonths(i);
            LocalDate expected = resolve(rule, target, median);
            // Em BUSINESS_DAY_FROM_END a relação é invertida: "1 dia útil antes
            // do fim" é uma data MAIOR que "3 dias úteis antes do fim". Comparar
            // as duas datas resolvidas (em vez de assumir min→earliest) acerta
            // os dois sentidos sem duplicar a regra por rule.
            LocalDate atMin = resolve(rule, target, min);
            LocalDate atMax = resolve(rule, target, max);
            LocalDate earliest = atMin.isBefore(atMax) ? atMin : atMax;
            LocalDate latest = atMin.isBefore(atMax) ? atMax : atMin;
            boolean adjusted = false;
            if ("CALENDAR_DAY".equals(rule)) {
                LocalDate raw = target.atDay(Math.min(median, target.lengthOfMonth()));
                if (!BrazilianBusinessDays.isBusinessDay(raw)) {
                    expected = BrazilianBusinessDays.previousBusinessDay(raw);
                    earliest = expected;
                    latest = BrazilianBusinessDays.nextBusinessDay(raw);
                    adjusted = true;
                }
            }
            upcoming.add(new IncomePatternResponse.Upcoming(target.toString(), expected, earliest, latest,
                    adjusted, origin, basis));
        }
        return upcoming;
    }

    private LocalDate resolve(String rule, YearMonth month, int n) {
        return switch (rule) {
            case "BUSINESS_DAY_FROM_START" -> BrazilianBusinessDays.nthBusinessDay(month, n);
            case "BUSINESS_DAY_FROM_END" -> BrazilianBusinessDays.nthBusinessDayFromEnd(month, n);
            default -> month.atDay(Math.min(Math.max(n, 1), month.lengthOfMonth()));
        };
    }

    // ------------------------------------------------------------ relação

    private IncomePatternResponse.Relation buildRelation(SourceData sd, List<LocalDate> salaryDates) {
        if (!VOUCHER_KINDS.contains(sd.kind()) || salaryDates.isEmpty() || sd.occurrences().isEmpty()) {
            return null;
        }
        List<Long> offsets = new ArrayList<>();
        for (Occ occ : sd.occurrences()) {
            salaryDates.stream()
                    .filter(s -> s.isAfter(occ.date()))
                    .min(Comparator.naturalOrder())
                    .ifPresent(salary -> {
                        long offset = BrazilianBusinessDays.businessDaysBetween(occ.date(), salary);
                        if (offset > 0 && offset <= 25) offsets.add(offset);
                    });
        }
        if (offsets.isEmpty()) return null;

        List<Integer> asInt = offsets.stream().map(Long::intValue).sorted().toList();
        int median = medianOf(asInt);
        int min = asInt.get(0);
        int max = asInt.get(asInt.size() - 1);
        boolean linked = asInt.size() >= 2 && (max - min) <= 2;
        String label = "cai " + median + " dia" + (median == 1 ? "" : "s") + " úteis antes do salário";
        return new IncomePatternResponse.Relation("SALARY",
                new IncomePatternResponse.Offset(median, min, max, asInt.size()), linked, label);
    }

    // ---------------------------------------------------------- inferência

    private List<PurchaseAdvisor.PurchaseRecord> groceryPurchases(UUID userId, LocalDate today) {
        Set<UUID> categoryIds = groceryCategoryIds();
        LocalDate from = today.minusMonths(GROCERY_WINDOW_MONTHS);
        List<BankTransaction> transactions = bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(userId,
                        from.atStartOfDay().atOffset(ZoneOffset.UTC),
                        today.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));

        List<PurchaseAdvisor.PurchaseRecord> result = new ArrayList<>();
        for (BankTransaction tx : transactions) {
            if (tx.isIgnored() || tx.isInternalTransfer() || tx.isRefunded()) continue;
            if (tx.getAmount() == null || tx.getAmount().signum() >= 0) continue;
            boolean isGrocery = (tx.getCategoryId() != null && categoryIds.contains(tx.getCategoryId()))
                    || matchesGroceryKeyword(tx.getNormalizedDescription());
            if (!isGrocery) continue;
            if (tx.getAmount().abs().compareTo(GROCERY_FLOOR) < 0) continue;
            result.add(new PurchaseAdvisor.PurchaseRecord(utcDay(tx.getDate()), tx.getAmount()));
        }
        return result;
    }

    private Set<UUID> groceryCategoryIds() {
        Set<UUID> ids = new HashSet<>();
        categoryRepository.findBySystemKeyAndUserIsNull("FOOD_GROCERIES").ifPresent(mercado -> {
            ids.add(mercado.getId());
            for (Category child : categoryRepository.findAllByParentId(mercado.getId())) {
                ids.add(child.getId());
            }
        });
        return ids;
    }

    private boolean matchesGroceryKeyword(String normalizedDescription) {
        if (normalizedDescription == null) return false;
        String text = normalizedDescription.toLowerCase(java.util.Locale.ROOT);
        for (String hint : GROCERY_HINTS) {
            if (text.contains(hint)) return true;
        }
        return false;
    }

    private int distinctMonths(List<PurchaseAdvisor.PurchaseRecord> purchases) {
        return (int) purchases.stream().map(p -> YearMonth.from(p.date())).distinct().count();
    }

    // ------------------------------------------------------------- conselho

    private record AdviceResult(IncomePatternResponse.Advice advice, boolean cardCycleUnknown) {
    }

    private AdviceResult buildAdvice(List<IncomePatternResponse.SourcePattern> sources,
                                      PurchasePreference preference, PurchaseAdvisor.CadenceInference inference,
                                      LocalDate today, UUID userId, List<LocalDate> salaryDates) {
        String cadence = preference != null ? preference.getCadence() : inference.cadence();
        String cadenceOrigin = preference != null ? "INFORMED" : "MEASURED";
        boolean weekendPreferred = preference != null
                ? preference.isWeekendPreferred()
                : inference.weekendShare() >= 0.5;
        String paymentMode = preference != null && preference.getPaymentMode() != null
                ? preference.getPaymentMode() : "CASH";

        IncomePatternResponse.SourcePattern funding = pickFundingSource(sources,
                preference != null ? preference.getFundingKind() : null);

        List<IncomePatternResponse.Upcoming> fundingUpcoming =
                funding != null ? funding.upcoming() : List.of();
        LocalDate fundingDate = !fundingUpcoming.isEmpty() ? fundingUpcoming.get(0).latest() : today;
        LocalDate mustLastUntil = fundingUpcoming.size() >= 2 ? fundingUpcoming.get(1).expected() : null;

        IncomePatternResponse.CardAdvice cardAdvice = null;
        LocalDate bestDay;
        List<LocalDate> nextDates = List.of();
        String weeklyDay = null;

        if ("CARD".equals(paymentMode)) {
            ConnectorAccount account = preference != null && preference.getCardAccountId() != null
                    ? connectorAccountRepository.findByIdAndUserId(preference.getCardAccountId(), userId).orElse(null)
                    : null;
            if (account == null || account.getStatementClosingDay() == null) {
                return new AdviceResult(null, true);
            }
            PurchaseAdvisor.CardRecommendation rec = advisor.cardAdvice(account.getStatementClosingDay(),
                    account.getStatementDueDay(), today, weekendPreferred, salaryDates);
            bestDay = rec.bestDay();
            cardAdvice = new IncomePatternResponse.CardAdvice(account.getId(), account.getName(),
                    account.getStatementClosingDay(), rec.nextClosing(), rec.invoiceDue(),
                    rec.paidBySalaryOn(), rec.warning());
            mustLastUntil = null;
            fundingDate = null;
        } else if ("WEEKLY".equals(cadence)) {
            weeklyDay = inference.weeklyDay() != null ? inference.weeklyDay() : DayOfWeek.SATURDAY.name();
            nextDates = advisor.weeklyNextDates(DayOfWeek.valueOf(weeklyDay), fundingDate, 4);
            bestDay = nextDates.get(0);
        } else {
            bestDay = advisor.bestDayCash(fundingDate, weekendPreferred);
        }

        Integer daysToCover = mustLastUntil != null
                ? (int) ChronoUnit.DAYS.between(bestDay, mustLastUntil) : null;

        String confidence = funding != null && funding.pattern() != null && funding.pattern().confidence() != null
                ? funding.pattern().confidence() : inference.confidence();

        IncomePatternResponse.Explanation explanation = buildExplanation(sources, funding, bestDay,
                mustLastUntil, daysToCover, cardAdvice, paymentMode);

        IncomePatternResponse.Basis basis = new IncomePatternResponse.Basis(
                funding != null && funding.pattern() != null ? funding.pattern().monthsObserved() : 0,
                funding != null && funding.pattern() != null ? funding.pattern().lastOccurrence() : null);

        IncomePatternResponse.Advice advice = new IncomePatternResponse.Advice(cadence, cadenceOrigin,
                paymentMode, bestDay, bestDay.getDayOfWeek().name(),
                funding != null ? funding.kind() : null, fundingDate, mustLastUntil, daysToCover,
                cardAdvice, weeklyDay, nextDates, confidence, explanation, basis);
        return new AdviceResult(advice, false);
    }

    /** FOOD_VOUCHER > MEAL_VOUCHER > SALARY, ou o que a preferência indicar — só entre quem tem previsão. */
    private IncomePatternResponse.SourcePattern pickFundingSource(List<IncomePatternResponse.SourcePattern> sources,
                                                                   String fundingKindPreference) {
        List<IncomePatternResponse.SourcePattern> usable =
                sources.stream().filter(sp -> !sp.upcoming().isEmpty()).toList();
        if (usable.isEmpty()) return null;
        if (fundingKindPreference != null) {
            var match = usable.stream().filter(sp -> sp.kind().equals(fundingKindPreference)).findFirst();
            if (match.isPresent()) return match.get();
        }
        for (String kind : List.of("FOOD_VOUCHER", "MEAL_VOUCHER", "SALARY")) {
            var match = usable.stream().filter(sp -> sp.kind().equals(kind)).findFirst();
            if (match.isPresent()) return match.get();
        }
        return usable.get(0);
    }

    private IncomePatternResponse.Explanation buildExplanation(List<IncomePatternResponse.SourcePattern> sources,
                                                                IncomePatternResponse.SourcePattern funding,
                                                                LocalDate bestDay, LocalDate mustLastUntil,
                                                                Integer daysToCover,
                                                                IncomePatternResponse.CardAdvice card,
                                                                String paymentMode) {
        List<String> lines = new ArrayList<>();

        sources.stream().filter(sp -> sp.kind().equals("SALARY")).findFirst().ifPresent(salary -> {
            if (salary.pattern() != null && !salary.upcoming().isEmpty()) {
                IncomePatternResponse.Upcoming next = salary.upcoming().get(0);
                String linha = "Seu salário cai " + salary.pattern().label()
                        + " (entre o " + ordinal(salary.pattern().min()) + " e o " + ordinal(salary.pattern().max())
                        + ", em " + salary.pattern().monthsObserved() + " meses) — em " + monthLabel(next.expected())
                        + " isso dá " + ddmm(next.expected()) + ".";
                lines.add(linha);
            } else if (salary.pattern() != null) {
                lines.add("Seu salário cai " + salary.pattern().label()
                        + " (dia informado por você, ainda sem histórico no extrato).");
            }
        });

        if (funding != null && funding.relation() != null && funding.relation().linked()) {
            // relation().label() já vem conjugado ("cai N dias úteis antes do
            // salário") — "costuma" na frente duplicaria o verbo
            lines.add("O " + funding.name() + " costuma " + funding.relation().label().replaceFirst("^cai\\b", "cair") + ".");
        }

        if ("CARD".equals(paymentMode) && card != null) {
            lines.add("Seu cartão fecha dia " + card.closingDay() + ": comprando a partir de "
                    + ddmm(bestDay) + ", a compra só entra na fatura que vence em "
                    + (card.invoiceDue() != null ? ddmm(card.invoiceDue()) : "data desconhecida")
                    + (card.paidBySalaryOn() != null
                        ? ", paga com o salário de " + monthLabel(card.paidBySalaryOn()) + "." : ".") );
            if (card.warning() != null) lines.add(card.warning());
        } else if (mustLastUntil != null) {
            lines.add("Comprando " + weekdayLabel(bestDay) + " " + ddmm(bestDay)
                    + ", a compra sai do dinheiro que já caiu e precisa durar até a próxima entrada, "
                    + "por volta de " + ddmm(mustLastUntil)
                    + (daysToCover != null ? " (" + daysToCover + " dias)." : "."));
        } else {
            lines.add("Melhor comprar " + weekdayLabel(bestDay) + " " + ddmm(bestDay) + ".");
        }

        if (funding != null && "INFORMED".equals(funding.origin())) {
            lines.add("(dia informado por você, ainda sem histórico no extrato)");
        }

        String headline = "Melhor dia para as compras: " + weekdayLabel(bestDay) + " " + ddmm(bestDay);
        return new IncomePatternResponse.Explanation(headline, lines);
    }

    // -------------------------------------------------------------- preferência

    @Transactional
    public IncomePatternResponse.Preference savePreference(String email, String cadence, Boolean weekendPreferred,
                                                            String paymentMode, UUID cardAccountId,
                                                            String fundingKind) {
        User user = requireUser(email);
        String normalizedCadence = parseCadence(cadence);
        String normalizedPaymentMode = parsePaymentMode(paymentMode);
        if ("CARD".equals(normalizedPaymentMode)) {
            if (cardAccountId == null) {
                throw new IllegalArgumentException("Informe o cartão quando o pagamento é CARD");
            }
            ConnectorAccount account = connectorAccountRepository.findByIdAndUserId(cardAccountId, user.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Cartão não encontrado"));
            if (!account.isCreditCard()) {
                throw new IllegalArgumentException("A conta indicada não é um cartão de crédito");
            }
        }
        parseFundingKind(fundingKind);

        PurchasePreference preference = purchasePreferenceRepository.findById(user.getId())
                .orElseGet(() -> PurchasePreference.builder().userId(user.getId()).build());
        preference.setCadence(normalizedCadence);
        preference.setWeekendPreferred(weekendPreferred == null || weekendPreferred);
        preference.setPaymentMode(normalizedPaymentMode);
        preference.setCardAccountId("CARD".equals(normalizedPaymentMode) ? cardAccountId : null);
        preference.setFundingKind(fundingKind != null ? fundingKind.trim().toUpperCase() : null);

        PurchasePreference saved = purchasePreferenceRepository.save(preference);
        return toPreferenceDto(saved);
    }

    @Transactional
    public void clearPreference(String email) {
        User user = requireUser(email);
        purchasePreferenceRepository.deleteById(user.getId());
    }

    private String parseCadence(String raw) {
        if (raw == null) throw new IllegalArgumentException("Informe a cadência: MONTHLY ou WEEKLY");
        try {
            return PurchasePreference.Cadence.valueOf(raw.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Cadência inválida: use MONTHLY ou WEEKLY");
        }
    }

    private String parsePaymentMode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return PurchasePreference.PaymentMode.valueOf(raw.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Forma de pagamento inválida: use CASH ou CARD");
        }
    }

    private void parseFundingKind(String raw) {
        if (raw == null || raw.isBlank()) return;
        try {
            IncomeSource.Kind kind = IncomeSource.Kind.valueOf(raw.trim().toUpperCase());
            if (kind != IncomeSource.Kind.SALARY && kind != IncomeSource.Kind.MEAL_VOUCHER
                    && kind != IncomeSource.Kind.FOOD_VOUCHER) {
                throw new IllegalArgumentException(
                        "Fonte de financiamento inválida: use SALARY, MEAL_VOUCHER ou FOOD_VOUCHER");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Fonte de financiamento inválida: use SALARY, MEAL_VOUCHER ou FOOD_VOUCHER");
        }
    }

    private IncomePatternResponse.Preference toPreferenceDto(PurchasePreference p) {
        return new IncomePatternResponse.Preference(p.getCadence(), p.isWeekendPreferred(), p.getPaymentMode(),
                p.getCardAccountId(), p.getFundingKind(), p.getUpdatedAt() != null
                ? p.getUpdatedAt().withOffsetSameInstant(ZoneOffset.UTC) : null);
    }

    // ------------------------------------------------------------------ util

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }

    private static LocalDate utcDay(OffsetDateTime date) {
        return date.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    }

    private static int medianOf(List<Integer> values) {
        List<Integer> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        if (n == 0) return 0;
        if (n % 2 == 1) return sorted.get(n / 2);
        return Math.round((sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0f);
    }

    private static BigDecimal medianAmount(List<Occ> occurrences) {
        List<BigDecimal> sorted = occurrences.stream().map(Occ::amount)
                .sorted().toList();
        int n = sorted.size();
        if (n == 0) return null;
        if (n % 2 == 1) return sorted.get(n / 2);
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    private static String ordinal(int n) {
        return n + "º";
    }

    private static String ddmm(LocalDate date) {
        return String.format("%02d/%02d", date.getDayOfMonth(), date.getMonthValue());
    }

    private static String monthLabel(LocalDate date) {
        return MESES[date.getMonthValue() - 1];
    }

    private static String weekdayLabel(LocalDate date) {
        return WEEKDAY_PT.get(date.getDayOfWeek());
    }
}
