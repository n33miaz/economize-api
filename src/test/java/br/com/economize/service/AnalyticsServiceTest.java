package br.com.economize.service;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.analytics.MonthlyAnalyticsResponse;
import br.com.economize.model.Category;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.wish.CycleCaveatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    private static final String EMAIL = "ana@economize.dev";

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    // As ressalvas (EC-138) são calculadas por serviço próprio; aqui o
    // interesse é a agregação, então o mock devolve lista vazia por padrão
    @Mock
    private CycleCaveatService cycleCaveatService;

    @InjectMocks
    private AnalyticsService service;

    private final User user = User.builder()
            .id(UUID.randomUUID()).name("Ana").email(EMAIL).password("x").build();

    private final YearMonth month = YearMonth.of(2026, 7);

    // materializa a projeção que o Spring Data devolveria da query agregada
    private record Row(UUID rowCategoryId, String rowType, BigDecimal rowTotal, long rowTxCount)
            implements BankTransactionRepository.CategoryTotal {
        @Override
        public UUID getCategoryId() {
            return rowCategoryId;
        }

        @Override
        public String getType() {
            return rowType;
        }

        @Override
        public BigDecimal getTotal() {
            return rowTotal;
        }

        @Override
        public long getTxCount() {
            return rowTxCount;
        }
    }

    @Test
    void monthlySplitsIncomeAndExpenseByTypeAndSign() {
        UUID foodId = UUID.randomUUID();
        UUID salaryId = UUID.randomUUID();
        UUID cashbackId = UUID.randomUUID();
        stubMonthly(
                List.of(new Row(foodId, "DEBIT", new BigDecimal("-250.00"), 3),
                        new Row(salaryId, "CREDIT", new BigDecimal("5000.00"), 1),
                        // tipos fora do padrão OFX caem na decisão pelo sinal do total
                        new Row(cashbackId, "OTHER", new BigDecimal("30.00"), 1),
                        new Row(foodId, "PAYMENT", new BigDecimal("-80.00"), 1)),
                List.of(), List.of(), 4L);

        MonthlyAnalyticsResponse response = service.analyze(EMAIL, AnalysisWindow.ofMonth(month));

        assertThat(response.month()).isEqualTo("2026-07");
        assertThat(response.totalIncome()).isEqualByComparingTo("5030.00");
        assertThat(response.totalExpense()).isEqualByComparingTo("330.00");
        assertThat(response.net()).isEqualByComparingTo("4700.00");
        assertThat(response.pendingReviewCount()).isEqualTo(4);
        assertThat(response.previous().month()).isEqualTo("2026-06");
        assertThat(response.previous().totalIncome()).isEqualByComparingTo("0");
        assertThat(response.previous().totalExpense()).isEqualByComparingTo("0");
    }

    @Test
    void monthlyOrdersSlicesByExpenseDescAndComputesDeltaAgainstPreviousMonth() {
        Category food = systemCategory("Alimentação");
        Category health = systemCategory("Saúde");
        Category salary = systemCategory("Salário");
        stubMonthly(
                List.of(new Row(food.getId(), "DEBIT", new BigDecimal("-250.00"), 3),
                        new Row(health.getId(), "DEBIT", new BigDecimal("-80.00"), 1),
                        new Row(salary.getId(), "CREDIT", new BigDecimal("5000.00"), 1)),
                List.of(new Row(food.getId(), "DEBIT", new BigDecimal("-200.00"), 2)),
                List.of(food, health, salary), 0L);

        MonthlyAnalyticsResponse response = service.analyze(EMAIL, AnalysisWindow.ofMonth(month));

        assertThat(response.categories())
                .extracting(MonthlyAnalyticsResponse.CategorySlice::categoryId)
                .containsExactly(food.getId(), health.getId(), salary.getId());

        MonthlyAnalyticsResponse.CategorySlice foodSlice = response.categories().get(0);
        assertThat(foodSlice.name()).isEqualTo("Alimentação");
        assertThat(foodSlice.system()).isTrue();
        assertThat(foodSlice.expenseTotal()).isEqualByComparingTo("250.00");
        assertThat(foodSlice.previousExpenseTotal()).isEqualByComparingTo("200.00");
        assertThat(foodSlice.expenseDeltaPct()).isEqualByComparingTo("25.0");

        // sem gasto no mês anterior não existe base de comparação — delta indefinido
        MonthlyAnalyticsResponse.CategorySlice healthSlice = response.categories().get(1);
        assertThat(healthSlice.previousExpenseTotal()).isEqualByComparingTo("0");
        assertThat(healthSlice.expenseDeltaPct()).isNull();
    }

    @Test
    void monthlyLabelsRowsWithoutCategoryAsSemCategoria() {
        stubMonthly(
                List.of(new Row(null, "DEBIT", new BigDecimal("-45.00"), 1)),
                List.of(), List.of(), 0L);

        MonthlyAnalyticsResponse response = service.analyze(EMAIL, AnalysisWindow.ofMonth(month));

        assertThat(response.categories()).hasSize(1);
        MonthlyAnalyticsResponse.CategorySlice slice = response.categories().get(0);
        assertThat(slice.categoryId()).isNull();
        assertThat(slice.name()).isEqualTo("Sem categoria");
        assertThat(slice.system()).isFalse();
        assertThat(slice.expenseTotal()).isEqualByComparingTo("45.00");
    }

    @Test
    void monthlyRollsSubcategoriesUpIntoTheParent() {
        Category food = systemCategory("Alimentação");
        Category delivery = subcategoryOf(food, "Delivery");
        Category market = subcategoryOf(food, "Mercado");
        stubMonthly(
                List.of(new Row(delivery.getId(), "DEBIT", new BigDecimal("-180.00"), 6),
                        new Row(market.getId(), "DEBIT", new BigDecimal("-420.00"), 4)),
                // o mês anterior também precisa somar o galho inteiro para o delta fechar
                List.of(new Row(delivery.getId(), "DEBIT", new BigDecimal("-300.00"), 5)),
                List.of(food, delivery, market), 0L);

        MonthlyAnalyticsResponse response = service.analyze(EMAIL, AnalysisWindow.ofMonth(month));

        assertThat(response.categories()).hasSize(1);
        MonthlyAnalyticsResponse.CategorySlice parent = response.categories().get(0);
        assertThat(parent.categoryId()).isEqualTo(food.getId());
        assertThat(parent.expenseTotal()).isEqualByComparingTo("600.00");
        assertThat(parent.txCount()).isEqualTo(10);
        assertThat(parent.previousExpenseTotal()).isEqualByComparingTo("300.00");
        assertThat(parent.expenseDeltaPct()).isEqualByComparingTo("100.0");

        assertThat(parent.children())
                .extracting(MonthlyAnalyticsResponse.CategorySlice::name)
                .containsExactly("Mercado", "Delivery");
        assertThat(parent.children().get(0).parentSystemKey()).isEqualTo("FOOD");
    }

    @Test
    void monthlyExposesWhatSitsDirectlyOnTheParentAsItsOwnLine() {
        Category food = systemCategory("Alimentação");
        Category delivery = subcategoryOf(food, "Delivery");
        stubMonthly(
                List.of(new Row(delivery.getId(), "DEBIT", new BigDecimal("-180.00"), 2),
                        new Row(food.getId(), "DEBIT", new BigDecimal("-20.00"), 1)),
                List.of(), List.of(food, delivery), 0L);

        MonthlyAnalyticsResponse response = service.analyze(EMAIL, AnalysisWindow.ofMonth(month));

        MonthlyAnalyticsResponse.CategorySlice parent = response.categories().get(0);
        assertThat(parent.expenseTotal()).isEqualByComparingTo("200.00");
        // sem essa linha a soma das filhas não bateria com o total do pai
        assertThat(parent.children())
                .extracting(MonthlyAnalyticsResponse.CategorySlice::name)
                .containsExactly("Delivery", "Sem subcategoria");
        assertThat(parent.children().get(1).expenseTotal()).isEqualByComparingTo("20.00");
    }

    @Test
    void categoryThatZeroedThisMonthStillShowsUpWithMinusOneHundred() {
        Category food = systemCategory("Alimentação");
        Category health = systemCategory("Saúde");
        stubMonthly(
                List.of(new Row(food.getId(), "DEBIT", new BigDecimal("-250.00"), 3)),
                // Saúde teve gasto no mês passado e nada neste
                List.of(new Row(food.getId(), "DEBIT", new BigDecimal("-200.00"), 2),
                        new Row(health.getId(), "DEBIT", new BigDecimal("-500.00"), 4)),
                List.of(food, health), 0L);

        MonthlyAnalyticsResponse response = service.analyze(EMAIL, AnalysisWindow.ofMonth(month));

        // a fatia zerada vai para o fim da lista, ordenada por gasto do mês
        assertThat(response.categories())
                .extracting(MonthlyAnalyticsResponse.CategorySlice::categoryId)
                .containsExactly(food.getId(), health.getId());

        MonthlyAnalyticsResponse.CategorySlice healthSlice = response.categories().get(1);
        assertThat(healthSlice.name()).isEqualTo("Saúde");
        assertThat(healthSlice.expenseTotal()).isEqualByComparingTo("0");
        assertThat(healthSlice.txCount()).isZero();
        assertThat(healthSlice.previousExpenseTotal()).isEqualByComparingTo("500.00");
        assertThat(healthSlice.expenseDeltaPct()).isEqualByComparingTo("-100.0");
    }

    @Test
    void subcategoryThatZeroedKeepsItsLineInsideTheParent() {
        Category food = systemCategory("Alimentação");
        Category delivery = subcategoryOf(food, "Delivery");
        Category market = subcategoryOf(food, "Mercado");
        stubMonthly(
                List.of(new Row(market.getId(), "DEBIT", new BigDecimal("-420.00"), 4)),
                List.of(new Row(market.getId(), "DEBIT", new BigDecimal("-400.00"), 4),
                        new Row(delivery.getId(), "DEBIT", new BigDecimal("-180.00"), 6)),
                List.of(food, delivery, market), 0L);

        MonthlyAnalyticsResponse response = service.analyze(EMAIL, AnalysisWindow.ofMonth(month));

        MonthlyAnalyticsResponse.CategorySlice parent = response.categories().get(0);
        assertThat(parent.children())
                .extracting(MonthlyAnalyticsResponse.CategorySlice::name)
                .containsExactly("Mercado", "Delivery");
        MonthlyAnalyticsResponse.CategorySlice deliverySlice = parent.children().get(1);
        assertThat(deliverySlice.expenseTotal()).isEqualByComparingTo("0");
        assertThat(deliverySlice.expenseDeltaPct()).isEqualByComparingTo("-100.0");
    }

    @Test
    void categoryWithoutExpenseInEitherMonthDoesNotCreateAnEmptySlice() {
        Category food = systemCategory("Alimentação");
        Category salary = systemCategory("Salário");
        stubMonthly(
                List.of(new Row(food.getId(), "DEBIT", new BigDecimal("-250.00"), 3)),
                // só receita no mês anterior: não vira fatia de gasto zerada
                List.of(new Row(salary.getId(), "CREDIT", new BigDecimal("5000.00"), 1)),
                List.of(food, salary), 0L);

        MonthlyAnalyticsResponse response = service.analyze(EMAIL, AnalysisWindow.ofMonth(month));

        assertThat(response.categories())
                .extracting(MonthlyAnalyticsResponse.CategorySlice::categoryId)
                .containsExactly(food.getId());
    }

    @Test
    void anchoredWindowAggregatesTheWindowAndComparesWithTheSameLengthBefore() {
        // ciclo do salário 12/07 -> 12/08 (32 dias): o comparável é 10/06 -> 11/07,
        // não junho do calendário — senão a variação mudaria só por causa do
        // tamanho do mês
        Category food = systemCategory("Alimentação");
        AnalysisWindow window = AnalysisWindow.of(LocalDate.of(2026, 7, 12), LocalDate.of(2026, 8, 12));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.sumByCategory(user.getId(),
                utc(2026, 7, 12), utc(2026, 8, 13)))
                .thenReturn(List.of(new Row(food.getId(), "DEBIT", new BigDecimal("-600.00"), 8)));
        when(bankTransactionRepository.sumByCategory(user.getId(),
                utc(2026, 6, 10), utc(2026, 7, 12)))
                .thenReturn(List.of(new Row(food.getId(), "DEBIT", new BigDecimal("-500.00"), 7)));
        when(categoryRepository.findVisibleTo(user.getId())).thenReturn(List.of(food));
        when(bankTransactionRepository.countByUserIdAndReviewStatusIn(eq(user.getId()), anyCollection()))
                .thenReturn(0L);

        MonthlyAnalyticsResponse response = service.analyze(EMAIL, window);

        // janela ancorada não pertence a mês nenhum: o rótulo some e o período
        // viaja em start/end
        assertThat(response.month()).isNull();
        assertThat(response.start()).isEqualTo(LocalDate.of(2026, 7, 12));
        assertThat(response.end()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(response.totalExpense()).isEqualByComparingTo("600.00");

        assertThat(response.previous().month()).isNull();
        assertThat(response.previous().start()).isEqualTo(LocalDate.of(2026, 6, 10));
        assertThat(response.previous().end()).isEqualTo(LocalDate.of(2026, 7, 11));
        assertThat(response.previous().totalExpense()).isEqualByComparingTo("500.00");

        MonthlyAnalyticsResponse.CategorySlice slice = response.categories().get(0);
        assertThat(slice.previousExpenseTotal()).isEqualByComparingTo("500.00");
        assertThat(slice.expenseDeltaPct()).isEqualByComparingTo("20.0");
    }

    @Test
    void monthModeStillReportsTheCalendarBoundsAndTheCalendarComparison() {
        stubMonthly(List.of(new Row(null, "DEBIT", new BigDecimal("-45.00"), 1)),
                List.of(), List.of(), 0L);

        MonthlyAnalyticsResponse response = service.analyze(EMAIL, AnalysisWindow.ofMonth(month));

        assertThat(response.month()).isEqualTo("2026-07");
        assertThat(response.start()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(response.end()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(response.previous().month()).isEqualTo("2026-06");
        assertThat(response.previous().start()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(response.previous().end()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    @DisplayName("A consolidação diz até que dia o extrato alcança (EC-137)")
    void monthlyExposesLastTransactionDate() {
        stubMonthly(List.of(), List.of(), List.of(), 0);
        OffsetDateTime max = OffsetDateTime.of(2026, 7, 28, 15, 30, 0, 0, ZoneOffset.UTC);
        when(bankTransactionRepository.findDateBounds(user.getId()))
                .thenReturn(Collections.singletonList(
                        new Object[]{OffsetDateTime.of(2026, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC), max}));

        var response = service.analyze(EMAIL, AnalysisWindow.ofMonth(month));

        // O dia, e não o instante: a pergunta do app é "o extrato já alcança
        // o dia 25?", e hora nenhuma muda essa resposta
        assertThat(response.lastTransactionDate()).isEqualTo(LocalDate.of(2026, 7, 28));
    }

    @Test
    @DisplayName("Sem transação nenhuma, a data mais recente é nula e não hoje")
    void monthlyLastTransactionDateIsNullWithoutData() {
        stubMonthly(List.of(), List.of(), List.of(), 0);
        when(bankTransactionRepository.findDateBounds(user.getId()))
                .thenReturn(Collections.singletonList(new Object[]{null, null}));

        var response = service.analyze(EMAIL, AnalysisWindow.ofMonth(month));

        // Nulo é "não sei"; hoje seria dizer que o extrato está em dia
        assertThat(response.lastTransactionDate()).isNull();
    }

    @Test
    void monthsWithDataEnumeratesMonthsBetweenBoundsDesc() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        OffsetDateTime min = OffsetDateTime.of(2026, 5, 10, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime max = OffsetDateTime.of(2026, 7, 2, 0, 0, 0, 0, ZoneOffset.UTC);
        when(bankTransactionRepository.findDateBounds(user.getId()))
                .thenReturn(Collections.singletonList(new Object[]{min, max}));

        assertThat(service.monthsWithData(EMAIL)).containsExactly("2026-07", "2026-06", "2026-05");
    }

    @Test
    void monthsWithDataReturnsEmptyWithoutRows() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.findDateBounds(user.getId())).thenReturn(List.of());

        assertThat(service.monthsWithData(EMAIL)).isEmpty();
    }

    @Test
    void monthsWithDataReturnsEmptyWhenBoundsAreNull() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        // min/max sobre tabela vazia vem como uma linha de nulls no JPQL
        when(bankTransactionRepository.findDateBounds(user.getId()))
                .thenReturn(Collections.singletonList(new Object[]{null, null}));

        assertThat(service.monthsWithData(EMAIL)).isEmpty();
    }

    private void stubMonthly(List<BankTransactionRepository.CategoryTotal> currentRows,
                             List<BankTransactionRepository.CategoryTotal> previousRows,
                             List<Category> catalog, long pendingCount) {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        OffsetDateTime currentStart = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime previousStart = month.minusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        when(bankTransactionRepository.sumByCategory(user.getId(), currentStart, currentStart.plusMonths(1)))
                .thenReturn(currentRows);
        when(bankTransactionRepository.sumByCategory(user.getId(), previousStart, previousStart.plusMonths(1)))
                .thenReturn(previousRows);
        when(categoryRepository.findVisibleTo(user.getId())).thenReturn(catalog);
        when(bankTransactionRepository.countByUserIdAndReviewStatusIn(eq(user.getId()), anyCollection()))
                .thenReturn(pendingCount);
    }

    private OffsetDateTime utc(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth).atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private Category systemCategory(String name) {
        return Category.builder()
                .id(UUID.randomUUID())
                .name(name)
                .slug(name.toLowerCase())
                .systemKey(name.startsWith("Alimenta") ? "FOOD" : null)
                .flow(Category.Flow.EXPENSE)
                .archived(false)
                .build();
    }

    private Category subcategoryOf(Category parent, String name) {
        Category child = systemCategory(name);
        child.setSystemKey(null);
        child.setParent(parent);
        return child;
    }
}
