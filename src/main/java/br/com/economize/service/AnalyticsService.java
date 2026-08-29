package br.com.economize.service;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.analytics.MonthlyAnalyticsResponse;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Consolidação de um período: entradas vs saídas, quebra por categoria e
 * comparação com o período anterior — os números da tela de Análise. Janela em
 * UTC porque os parsers gravam as datas do extrato sem fuso (meia-noite UTC).
 *
 * <p>O período é sempre um {@link AnalysisWindow}: mês do calendário ou janela
 * ancorada no dia do salário (EC-092). A agregação não sabe a diferença — quem
 * decide o recorte e o comparável é a janela.
 *
 * <p>A data considerada é a {@code date} da transação, isto é, a data de
 * LANÇAMENTO que o extrato informou (DTPOSTED no OFX, coluna "Data" no CSV/XLSX,
 * {@code date} no conector). Não existe data de liquidação no modelo: nenhuma
 * fonte de importação entrega as duas.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final EnumSet<BankTransaction.ReviewStatus> PENDING =
            EnumSet.of(BankTransaction.ReviewStatus.SUGGESTED, BankTransaction.ReviewStatus.UNCATEGORIZED);

    private final BankTransactionRepository bankTransactionRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    public MonthlyAnalyticsResponse analyze(String email, AnalysisWindow window) {
        User user = requireUser(email);

        AnalysisWindow previousWindow = window.previous();
        Totals current = totalsFor(user.getId(), window);
        Totals previous = totalsFor(user.getId(), previousWindow);

        Map<UUID, Category> catalog = categoryRepository.findVisibleTo(user.getId()).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));

        List<MonthlyAnalyticsResponse.CategorySlice> slices = rollUp(current, previous, catalog);

        long pendingReview = bankTransactionRepository.countByUserIdAndReviewStatusIn(user.getId(), PENDING);

        return new MonthlyAnalyticsResponse(
                window.monthLabel(),
                window.start(), window.end(),
                current.income, current.expense, current.income.subtract(current.expense),
                new MonthlyAnalyticsResponse.MonthTotals(
                        previousWindow.monthLabel(),
                        previousWindow.start(), previousWindow.end(),
                        previous.income, previous.expense, previous.income.subtract(previous.expense)),
                slices,
                pendingReview);
    }

    /**
     * Meses com movimento, do mais recente ao mais antigo — alimenta o seletor.
     */
    public List<String> monthsWithData(String email) {
        User user = requireUser(email);
        List<Object[]> bounds = bankTransactionRepository.findDateBounds(user.getId());
        if (bounds.isEmpty() || bounds.get(0)[0] == null) return List.of();

        YearMonth first = YearMonth.from(((OffsetDateTime) bounds.get(0)[0]).atZoneSameInstant(ZoneOffset.UTC));
        YearMonth last = YearMonth.from(((OffsetDateTime) bounds.get(0)[1]).atZoneSameInstant(ZoneOffset.UTC));

        List<String> months = new ArrayList<>();
        for (YearMonth ym = last; !ym.isBefore(first); ym = ym.minusMonths(1)) {
            months.add(ym.toString());
        }
        return months;
    }

    /**
     * Agrupa as fatias pela raiz: uma transação em "Alimentação > Delivery" soma
     * em Alimentação e aparece detalhada dentro dela. O que estiver direto no pai
     * vira uma linha "Sem subcategoria" só quando há filhas — senão a soma das
     * filhas não bateria com o total do pai e a tela mentiria.
     */
    private List<MonthlyAnalyticsResponse.CategorySlice> rollUp(
            Totals current, Totals previous, Map<UUID, Category> catalog) {

        Map<UUID, List<UUID>> childrenOf = new LinkedHashMap<>();
        Map<UUID, CategoryAgg> rootAgg = new LinkedHashMap<>();

        for (Map.Entry<UUID, CategoryAgg> entry : current.byCategory.entrySet()) {
            UUID rootId = rootIdOf(entry.getKey(), catalog);
            rootAgg.computeIfAbsent(rootId, id -> new CategoryAgg()).add(entry.getValue());
            if (!java.util.Objects.equals(rootId, entry.getKey())) {
                childrenOf.computeIfAbsent(rootId, id -> new ArrayList<>()).add(entry.getKey());
            }
        }

        // Quem gastou no mês passado e nada neste precisa aparecer zerado, com
        // o delta de -100%. Nascendo só do mês corrente, a categoria sumia da
        // tela sem deixar rastro e a queda passava por engano de importação.
        for (Map.Entry<UUID, CategoryAgg> entry : previous.byCategory.entrySet()) {
            if (entry.getValue().expense.signum() == 0) continue;
            UUID rootId = rootIdOf(entry.getKey(), catalog);
            rootAgg.computeIfAbsent(rootId, id -> new CategoryAgg());
            if (!java.util.Objects.equals(rootId, entry.getKey())) {
                List<UUID> siblings = childrenOf.computeIfAbsent(rootId, id -> new ArrayList<>());
                if (!siblings.contains(entry.getKey())) siblings.add(entry.getKey());
            }
        }

        List<MonthlyAnalyticsResponse.CategorySlice> roots = new ArrayList<>();
        for (Map.Entry<UUID, CategoryAgg> entry : rootAgg.entrySet()) {
            UUID rootId = entry.getKey();
            List<UUID> childIds = childrenOf.getOrDefault(rootId, List.of());

            List<MonthlyAnalyticsResponse.CategorySlice> children = new ArrayList<>();
            for (UUID childId : childIds) {
                children.add(slice(childId, current.byCategory.get(childId),
                        previous.byCategory.get(childId), catalog, List.of()));
            }
            CategoryAgg own = current.byCategory.get(rootId);
            if (!childIds.isEmpty() && own != null && own.count > 0) {
                children.add(new MonthlyAnalyticsResponse.CategorySlice(
                        rootId, "Sem subcategoria", null, null, null, null,
                        rootId != null && catalog.get(rootId) != null ? catalog.get(rootId).getSystemKey() : null,
                        false, own.expense, own.income, own.count, BigDecimal.ZERO, null, List.of()));
            }
            children.sort(Comparator.comparing(MonthlyAnalyticsResponse.CategorySlice::expenseTotal).reversed());

            CategoryAgg prevRoot = previousRootAgg(rootId, previous, catalog);
            roots.add(slice(rootId, entry.getValue(), prevRoot, catalog, children));
        }
        roots.sort(Comparator.comparing(MonthlyAnalyticsResponse.CategorySlice::expenseTotal).reversed());
        return roots;
    }

    private CategoryAgg previousRootAgg(UUID rootId, Totals previous, Map<UUID, Category> catalog) {
        CategoryAgg sum = new CategoryAgg();
        for (Map.Entry<UUID, CategoryAgg> entry : previous.byCategory.entrySet()) {
            if (java.util.Objects.equals(rootIdOf(entry.getKey(), catalog), rootId)) sum.add(entry.getValue());
        }
        return sum;
    }

    private UUID rootIdOf(UUID categoryId, Map<UUID, Category> catalog) {
        if (categoryId == null) return null;
        Category category = catalog.get(categoryId);
        if (category == null) return categoryId;
        return category.rootCategory().getId();
    }

    private MonthlyAnalyticsResponse.CategorySlice slice(
            UUID categoryId, CategoryAgg agg, CategoryAgg prevAgg,
            Map<UUID, Category> catalog, List<MonthlyAnalyticsResponse.CategorySlice> children) {

        Category category = categoryId != null ? catalog.get(categoryId) : null;
        CategoryAgg safe = agg != null ? agg : new CategoryAgg();
        BigDecimal prevExpense = prevAgg != null ? prevAgg.expense : BigDecimal.ZERO;
        Category parent = category != null ? category.getParent() : null;

        return new MonthlyAnalyticsResponse.CategorySlice(
                categoryId,
                category != null ? category.getName() : "Sem categoria",
                category != null ? category.getGroupName() : null,
                category != null ? category.getColor() : null,
                category != null ? category.getIcon() : null,
                category != null ? category.getSystemKey() : null,
                parent != null ? parent.getSystemKey() : null,
                category != null && category.getUser() == null,
                safe.expense,
                safe.income,
                safe.count,
                prevExpense,
                deltaPct(safe.expense, prevExpense),
                children);
    }

    private Totals totalsFor(UUID userId, AnalysisWindow window) {
        List<BankTransactionRepository.CategoryTotal> rows = bankTransactionRepository.sumByCategory(
                userId, window.startInstant(), window.endExclusiveInstant());

        Totals totals = new Totals();
        for (BankTransactionRepository.CategoryTotal row : rows) {
            BigDecimal abs = row.getTotal() != null ? row.getTotal().abs() : BigDecimal.ZERO;
            // OFX pode trazer TRNTYPE fora do padrão; nesse caso o sinal decide
            boolean income = "CREDIT".equalsIgnoreCase(row.getType())
                    || (!"DEBIT".equalsIgnoreCase(row.getType())
                    && row.getTotal() != null && row.getTotal().signum() > 0);

            CategoryAgg agg = totals.byCategory.computeIfAbsent(row.getCategoryId(), id -> new CategoryAgg());
            agg.count += row.getTxCount();
            if (income) {
                agg.income = agg.income.add(abs);
                totals.income = totals.income.add(abs);
            } else {
                agg.expense = agg.expense.add(abs);
                totals.expense = totals.expense.add(abs);
            }
        }
        return totals;
    }

    private BigDecimal deltaPct(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.signum() == 0) return null;
        return current.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, 1, RoundingMode.HALF_UP);
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }

    private static class Totals {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        final Map<UUID, CategoryAgg> byCategory = new HashMap<>();
    }

    private static class CategoryAgg {
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal income = BigDecimal.ZERO;
        long count;

        void add(CategoryAgg other) {
            this.expense = this.expense.add(other.expense);
            this.income = this.income.add(other.income);
            this.count += other.count;
        }
    }
}
