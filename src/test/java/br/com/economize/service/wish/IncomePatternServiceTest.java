package br.com.economize.service.wish;

import br.com.economize.dto.analytics.IncomePatternResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O padrão de entradas em dia útil — fixture real do dono (salário 03/06,
 * 02/07, 05/08, 04/09/2026; VR Flash 25/06, 29/07, 28/08 + recarga de 07/07 a
 * descartar) e cenários sintéticos verificados contra {@link BrazilianBusinessDays}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncomePatternServiceTest {

    private static final String EMAIL = "dono@economize.app";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final LocalDate HOJE = LocalDate.of(2026, 9, 15);

    @Mock
    private IncomeSourceRepository incomeSourceRepository;
    @Mock
    private RecurringSeriesRepository recurringSeriesRepository;
    @Mock
    private RecurringSeriesLinkRepository linkRepository;
    @Mock
    private BankTransactionRepository bankTransactionRepository;
    @Mock
    private ConnectorAccountRepository connectorAccountRepository;
    @Mock
    private PurchasePreferenceRepository purchasePreferenceRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private UserRepository userRepository;

    private IncomePatternService service;

    @BeforeEach
    void setUp() {
        service = new IncomePatternService(incomeSourceRepository, recurringSeriesRepository, linkRepository,
                bankTransactionRepository, connectorAccountRepository, purchasePreferenceRepository,
                categoryRepository, userRepository, new PurchaseAdvisor());

        User user = User.builder().id(USER_ID).email(EMAIL).build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(purchasePreferenceRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(categoryRepository.findBySystemKeyAndUserIsNull(any())).thenReturn(Optional.empty());
        when(bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(eq(USER_ID), any(), any()))
                .thenReturn(List.of());
        when(recurringSeriesRepository.findAllByUserId(USER_ID)).thenReturn(List.of());
    }

    @Test
    @DisplayName("VR Flash: 3 ocorrências (recarga de R$24 descartada), FROM_END, confiança MEDIUM")
    void flashVoucherFixtureDiscardsExtraTopUpAndFindsFromEndPattern() {
        UUID seriesId = UUID.randomUUID();
        IncomeSource vr = IncomeSource.builder().id(UUID.randomUUID()).kind(IncomeSource.Kind.MEAL_VOUCHER)
                .name("Flash — Vale refeição").confirmed(true).active(true).seriesId(seriesId).build();
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER_ID)).thenReturn(List.of(vr));

        UUID tx1 = UUID.randomUUID();
        UUID tx2 = UUID.randomUUID(); // recarga extra, mesmo mês do tx3
        UUID tx3 = UUID.randomUUID();
        UUID tx4 = UUID.randomUUID();
        givenSeriesLinks(seriesId, tx1, tx2, tx3, tx4);
        when(bankTransactionRepository.findAllByUserIdAndIdIn(eq(USER_ID), anyCollection())).thenReturn(List.of(
                credit(tx1, LocalDate.of(2026, 6, 25), "770.00", false),
                credit(tx2, LocalDate.of(2026, 7, 7), "24.00", false),
                credit(tx3, LocalDate.of(2026, 7, 29), "735.00", false),
                credit(tx4, LocalDate.of(2026, 8, 28), "735.00", false)));

        IncomePatternResponse response = service.analyze(EMAIL, HOJE);

        IncomePatternResponse.SourcePattern source = response.sources().get(0);
        assertThat(source.occurrences()).hasSize(3);
        assertThat(source.occurrences()).extracting(IncomePatternResponse.Occurrence::amount)
                .doesNotContain(new BigDecimal("24.00"));
        assertThat(source.pattern().monthsObserved()).isEqualTo(3);
        assertThat(source.pattern().rule()).isEqualTo("BUSINESS_DAY_FROM_END");
        assertThat(source.pattern().median()).isEqualTo(2);
        assertThat(source.pattern().min()).isEqualTo(1);
        assertThat(source.pattern().max()).isEqualTo(3);
        assertThat(source.pattern().confidence()).isEqualTo("MEDIUM");
    }

    @Test
    @DisplayName("Salário 7 meses no 4º-6º dia útil: FROM_START, mediana 5, upcoming[0]=07/10")
    void salaryWithSevenMonthsFindsFromStartPatternAndPredictsOctober() {
        UUID seriesId = UUID.randomUUID();
        IncomeSource salary = IncomeSource.builder().id(UUID.randomUUID()).kind(IncomeSource.Kind.SALARY)
                .name("Salário").confirmed(true).active(true).seriesId(seriesId).build();
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER_ID)).thenReturn(List.of(salary));

        // meses (mês, posição do dia útil): median = 5, min = 4, max = 6
        int[][] months = {{3, 4}, {4, 5}, {5, 5}, {6, 6}, {7, 5}, {8, 4}, {9, 6}};
        List<UUID> ids = new ArrayList<>();
        List<BankTransaction> transactions = new ArrayList<>();
        for (int[] m : months) {
            UUID id = UUID.randomUUID();
            LocalDate date = BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, m[0]), m[1]);
            ids.add(id);
            transactions.add(credit(id, date, "4400.00", false));
        }
        givenSeriesLinks(seriesId, ids.toArray(new UUID[0]));
        when(bankTransactionRepository.findAllByUserIdAndIdIn(eq(USER_ID), anyCollection())).thenReturn(transactions);

        IncomePatternResponse response = service.analyze(EMAIL, HOJE);

        IncomePatternResponse.SourcePattern source = response.sources().get(0);
        assertThat(source.pattern().rule()).isEqualTo("BUSINESS_DAY_FROM_START");
        assertThat(source.pattern().median()).isEqualTo(5);
        assertThat(source.pattern().min()).isEqualTo(4);
        assertThat(source.pattern().max()).isEqualTo(6);
        assertThat(source.pattern().monthsObserved()).isEqualTo(7);
        assertThat(source.upcoming().get(0).expected()).isEqualTo(LocalDate.of(2026, 10, 7));
    }

    @Test
    @DisplayName("Relação VR->salário: pareamento fica LINKED com offset mediano de 5 dias úteis")
    void relationBetweenVoucherAndSalaryIsLinkedWithMedianOffset() {
        UUID salarySeriesId = UUID.randomUUID();
        UUID vrSeriesId = UUID.randomUUID();
        IncomeSource salary = IncomeSource.builder().id(UUID.randomUUID()).kind(IncomeSource.Kind.SALARY)
                .name("Salário").confirmed(true).active(true).seriesId(salarySeriesId).build();
        IncomeSource vr = IncomeSource.builder().id(UUID.randomUUID()).kind(IncomeSource.Kind.MEAL_VOUCHER)
                .name("Flash").confirmed(true).active(true).seriesId(vrSeriesId).build();
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER_ID)).thenReturn(List.of(salary, vr));

        List<LocalDate> salaryDates = List.of(
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 7), 5),
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 8), 5),
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 9), 5));
        List<LocalDate> vrDates = salaryDates.stream()
                .map(d -> BrazilianBusinessDays.plusBusinessDays(d, -5)).toList();

        List<UUID> salaryTxIds = new ArrayList<>();
        List<BankTransaction> salaryTxs = new ArrayList<>();
        for (LocalDate d : salaryDates) {
            UUID id = UUID.randomUUID();
            salaryTxIds.add(id);
            salaryTxs.add(credit(id, d, "4400.00", false));
        }
        List<UUID> vrTxIds = new ArrayList<>();
        List<BankTransaction> vrTxs = new ArrayList<>();
        for (LocalDate d : vrDates) {
            UUID id = UUID.randomUUID();
            vrTxIds.add(id);
            vrTxs.add(credit(id, d, "735.00", false));
        }

        when(linkRepository.findAllBySeriesIdIn(List.of(salarySeriesId)))
                .thenReturn(linksFor(salarySeriesId, salaryTxIds.toArray(new UUID[0])));
        when(linkRepository.findAllBySeriesIdIn(List.of(vrSeriesId)))
                .thenReturn(linksFor(vrSeriesId, vrTxIds.toArray(new UUID[0])));
        when(bankTransactionRepository.findAllByUserIdAndIdIn(eq(USER_ID), eq(salaryTxIds))).thenReturn(salaryTxs);
        when(bankTransactionRepository.findAllByUserIdAndIdIn(eq(USER_ID), eq(vrTxIds))).thenReturn(vrTxs);

        IncomePatternResponse response = service.analyze(EMAIL, HOJE);

        IncomePatternResponse.SourcePattern vrPattern = response.sources().stream()
                .filter(s -> s.kind().equals("MEAL_VOUCHER")).findFirst().orElseThrow();
        assertThat(vrPattern.relation()).isNotNull();
        assertThat(vrPattern.relation().to()).isEqualTo("SALARY");
        assertThat(vrPattern.relation().offsetBusinessDays().median()).isEqualTo(5);
        assertThat(vrPattern.relation().offsetBusinessDays().pairs()).isEqualTo(3);
        assertThat(vrPattern.relation().linked()).isTrue();
    }

    @Test
    @DisplayName("Fonte informada sem série: CALENDAR_DAY a partir do anchorDay, com ajuste de fim de semana")
    void informedSourceWithoutSeriesUsesCalendarDayAndAdjustsWeekend() {
        // 03/10/2026 é sábado: o dia 3 declarado cai em fim de semana no próximo mês
        IncomeSource declared = IncomeSource.builder().id(UUID.randomUUID()).kind(IncomeSource.Kind.SALARY)
                .name("Salário (informado)").confirmed(true).active(true).anchorDay((short) 3)
                .expectedAmount(new BigDecimal("3000.00")).build();
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER_ID)).thenReturn(List.of(declared));

        IncomePatternResponse response = service.analyze(EMAIL, HOJE);

        IncomePatternResponse.SourcePattern source = response.sources().get(0);
        assertThat(source.origin()).isEqualTo("INFORMED");
        assertThat(source.pattern().rule()).isEqualTo("CALENDAR_DAY");
        assertThat(source.pattern().confidence()).isNull();
        assertThat(source.pattern().monthsObserved()).isZero();
        assertThat(source.upcoming()).isNotEmpty();
        assertThat(source.upcoming().get(0).adjusted()).isTrue();
    }

    @Test
    @DisplayName("Duas ocorrências não bastam: INSUFFICIENT_HISTORY com a mensagem certa")
    void twoOccurrencesIsInsufficientHistory() {
        UUID seriesId = UUID.randomUUID();
        IncomeSource vr = IncomeSource.builder().id(UUID.randomUUID()).kind(IncomeSource.Kind.MEAL_VOUCHER)
                .name("Flash").confirmed(true).active(true).seriesId(seriesId).build();
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER_ID)).thenReturn(List.of(vr));

        UUID tx1 = UUID.randomUUID();
        UUID tx2 = UUID.randomUUID();
        givenSeriesLinks(seriesId, tx1, tx2);
        when(bankTransactionRepository.findAllByUserIdAndIdIn(eq(USER_ID), anyCollection())).thenReturn(List.of(
                credit(tx1, LocalDate.of(2026, 7, 29), "735.00", false),
                credit(tx2, LocalDate.of(2026, 8, 28), "735.00", false)));

        IncomePatternResponse response = service.analyze(EMAIL, HOJE);

        assertThat(response.status()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(response.message()).contains("2 pagamentos");
        assertThat(response.sources().get(0).pattern()).isNull();
    }

    @Test
    @DisplayName("Sem fonte declarada, cai nas séries INCOME como sugestão não confirmada")
    void withoutAnyIncomeSourceFallsBackToIncomeSeries() {
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER_ID)).thenReturn(List.of());

        UUID seriesId = UUID.randomUUID();
        RecurringSeries series = RecurringSeries.builder().id(seriesId).flow(RecurringSeries.Flow.INCOME)
                .cadence(RecurringSeries.Cadence.MONTHLY).active(true).dismissed(false)
                .displayName("Salário Empresa X").merchantKey("salario empresa x").build();
        when(recurringSeriesRepository.findAllByUserId(USER_ID)).thenReturn(List.of(series));

        UUID tx1 = UUID.randomUUID();
        UUID tx2 = UUID.randomUUID();
        UUID tx3 = UUID.randomUUID();
        givenSeriesLinks(seriesId, tx1, tx2, tx3);
        when(bankTransactionRepository.findAllByUserIdAndIdIn(eq(USER_ID), anyCollection())).thenReturn(List.of(
                credit(tx1, LocalDate.of(2026, 7, 3), "4400.00", false),
                credit(tx2, LocalDate.of(2026, 8, 4), "4400.00", false),
                credit(tx3, LocalDate.of(2026, 9, 3), "4400.00", false)));

        IncomePatternResponse response = service.analyze(EMAIL, HOJE);

        IncomePatternResponse.SourcePattern source = response.sources().get(0);
        assertThat(source.confirmed()).isFalse();
        assertThat(source.origin()).isEqualTo("MEASURED");
        assertThat(source.incomeSourceId()).isNull();
        assertThat(source.seriesId()).isEqualTo(seriesId);
        assertThat(source.kind()).isEqualTo("SALARY");
    }

    @Test
    @DisplayName("Dois créditos no mesmo dia (duas origens do mesmo VR) contam uma vez só")
    void twoCreditsOnTheSameDayAreDeduped() {
        UUID seriesId = UUID.randomUUID();
        IncomeSource vr = IncomeSource.builder().id(UUID.randomUUID()).kind(IncomeSource.Kind.MEAL_VOUCHER)
                .name("Flash").confirmed(true).active(true).seriesId(seriesId).build();
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER_ID)).thenReturn(List.of(vr));

        UUID tx1a = UUID.randomUUID();
        UUID tx1b = UUID.randomUUID(); // mesmo dia do tx1a: PIX no Inter e depósito no CSV da Flash
        UUID tx2 = UUID.randomUUID();
        UUID tx3 = UUID.randomUUID();
        givenSeriesLinks(seriesId, tx1a, tx1b, tx2, tx3);
        when(bankTransactionRepository.findAllByUserIdAndIdIn(eq(USER_ID), anyCollection())).thenReturn(List.of(
                credit(tx1a, LocalDate.of(2026, 6, 25), "770.00", false),
                credit(tx1b, LocalDate.of(2026, 6, 25), "770.00", false),
                credit(tx2, LocalDate.of(2026, 7, 29), "735.00", false),
                credit(tx3, LocalDate.of(2026, 8, 28), "735.00", false)));

        IncomePatternResponse response = service.analyze(EMAIL, HOJE);

        assertThat(response.sources().get(0).occurrences()).hasSize(3);
    }

    @Test
    @DisplayName("Transação marcada como ignorada não vira ocorrência")
    void ignoredTransactionsAreExcluded() {
        UUID seriesId = UUID.randomUUID();
        IncomeSource vr = IncomeSource.builder().id(UUID.randomUUID()).kind(IncomeSource.Kind.MEAL_VOUCHER)
                .name("Flash").confirmed(true).active(true).seriesId(seriesId).build();
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER_ID)).thenReturn(List.of(vr));

        UUID tx1 = UUID.randomUUID();
        UUID tx2 = UUID.randomUUID();
        UUID txIgnored = UUID.randomUUID();
        givenSeriesLinks(seriesId, tx1, tx2, txIgnored);
        when(bankTransactionRepository.findAllByUserIdAndIdIn(eq(USER_ID), anyCollection())).thenReturn(List.of(
                credit(tx1, LocalDate.of(2026, 7, 29), "735.00", false),
                credit(tx2, LocalDate.of(2026, 8, 28), "735.00", false),
                credit(txIgnored, LocalDate.of(2026, 9, 10), "735.00", true)));

        IncomePatternResponse response = service.analyze(EMAIL, HOJE);

        assertThat(response.sources().get(0).occurrences()).hasSize(2);
        assertThat(response.status()).isEqualTo("INSUFFICIENT_HISTORY");
    }

    @Test
    @DisplayName("Cartão: fecha dia 10, sem preferência de fim de semana, salário paga a fatura a tempo")
    void cardPreferenceBuildsCardAdviceWithoutWarning() {
        IncomeSource salary = givenSalarySource(
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 7), 5),
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 8), 5),
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 9), 5));
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER_ID)).thenReturn(List.of(salary));

        UUID cardId = UUID.randomUUID();
        PurchasePreference preference = PurchasePreference.builder().userId(USER_ID)
                .cadence("MONTHLY").weekendPreferred(false).paymentMode("CARD").cardAccountId(cardId).build();
        when(purchasePreferenceRepository.findById(USER_ID)).thenReturn(Optional.of(preference));
        ConnectorAccount card = ConnectorAccount.builder().id(cardId).name("Nubank")
                .type(ConnectorAccount.AccountType.CREDIT_CARD)
                .statementClosingDay(10).statementDueDay(17).build();
        when(connectorAccountRepository.findByIdAndUserId(cardId, USER_ID)).thenReturn(Optional.of(card));

        IncomePatternResponse response = service.analyze(EMAIL, LocalDate.of(2026, 10, 1));

        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.advice().paymentMode()).isEqualTo("CARD");
        assertThat(response.advice().bestDay()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(response.advice().card()).isNotNull();
        assertThat(response.advice().card().closingDay()).isEqualTo(10);
        assertThat(response.advice().card().name()).isEqualTo("Nubank");
        assertThat(response.advice().explanation().lines())
                .anyMatch(line -> line.contains("fecha dia 10"));
    }

    @Test
    @DisplayName("Cartão sem dia de fechamento conhecido: status CARD_CYCLE_UNKNOWN")
    void cardWithoutClosingDayIsCardCycleUnknown() {
        IncomeSource salary = givenSalarySource(
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 7), 5),
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 8), 5),
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 9), 5));
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER_ID)).thenReturn(List.of(salary));

        UUID cardId = UUID.randomUUID();
        PurchasePreference preference = PurchasePreference.builder().userId(USER_ID)
                .cadence("MONTHLY").weekendPreferred(false).paymentMode("CARD").cardAccountId(cardId).build();
        when(purchasePreferenceRepository.findById(USER_ID)).thenReturn(Optional.of(preference));
        ConnectorAccount card = ConnectorAccount.builder().id(cardId).name("Conta sem fechamento")
                .type(ConnectorAccount.AccountType.CREDIT_CARD).build();
        when(connectorAccountRepository.findByIdAndUserId(cardId, USER_ID)).thenReturn(Optional.of(card));

        IncomePatternResponse response = service.analyze(EMAIL, HOJE);

        assertThat(response.status()).isEqualTo("CARD_CYCLE_UNKNOWN");
        assertThat(response.advice()).isNull();
    }

    @Test
    @DisplayName("Preferência semanal: nextDates tem 4 datas e bestDay é a primeira delas")
    void weeklyPreferenceBuildsFourNextDates() {
        IncomeSource salary = givenSalarySource(
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 7), 5),
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 8), 5),
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 9), 5));
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER_ID)).thenReturn(List.of(salary));

        PurchasePreference preference = PurchasePreference.builder().userId(USER_ID)
                .cadence("WEEKLY").weekendPreferred(true).build();
        when(purchasePreferenceRepository.findById(USER_ID)).thenReturn(Optional.of(preference));

        IncomePatternResponse response = service.analyze(EMAIL, HOJE);

        assertThat(response.advice().cadence()).isEqualTo("WEEKLY");
        assertThat(response.advice().nextDates()).hasSize(4);
        assertThat(response.advice().bestDay()).isEqualTo(response.advice().nextDates().get(0));
        assertThat(response.advice().weeklyDay()).isNotNull();
    }

    @Test
    @DisplayName("fundingKind declarado escolhe a fonte mesmo quando não é a prioridade padrão")
    void declaredFundingKindOverridesDefaultPriority() {
        IncomeSource salary = givenSalarySource(
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 7), 5),
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 8), 5),
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 9), 5));
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER_ID)).thenReturn(List.of(salary));

        PurchasePreference preference = PurchasePreference.builder().userId(USER_ID)
                .cadence("MONTHLY").weekendPreferred(false).fundingKind("SALARY").build();
        when(purchasePreferenceRepository.findById(USER_ID)).thenReturn(Optional.of(preference));

        IncomePatternResponse response = service.analyze(EMAIL, HOJE);

        assertThat(response.advice().fundingSource()).isEqualTo("SALARY");
    }

    @Test
    @DisplayName("Compra de mercado por categoria e por palavra-chave alimenta a inferência de cadência")
    void groceryPurchasesMatchByCategoryAndByKeyword() {
        IncomeSource salary = givenSalarySource(
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 7), 5),
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 8), 5),
                BrazilianBusinessDays.nthBusinessDay(YearMonth.of(2026, 9), 5));
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER_ID)).thenReturn(List.of(salary));

        UUID mercadoId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        Category mercado = Category.builder().id(mercadoId).build();
        Category filha = Category.builder().id(childId).build();
        when(categoryRepository.findBySystemKeyAndUserIsNull("FOOD_GROCERIES")).thenReturn(Optional.of(mercado));
        when(categoryRepository.findAllByParentId(mercadoId)).thenReturn(List.of(filha));

        BankTransaction porCategoria = grocery(LocalDate.of(2026, 9, 5), "300.00");
        porCategoria.setCategoryId(childId);
        BankTransaction porPalavraChave = grocery(LocalDate.of(2026, 9, 12), "150.00");
        porPalavraChave.setNormalizedDescription("supermercado bom preco");
        BankTransaction abaixoDoPiso = grocery(LocalDate.of(2026, 9, 19), "10.00");
        abaixoDoPiso.setCategoryId(childId);
        BankTransaction ignorada = grocery(LocalDate.of(2026, 9, 20), "200.00");
        ignorada.setCategoryId(childId);
        ignorada.setIgnored(true);
        BankTransaction transferenciaInterna = grocery(LocalDate.of(2026, 9, 21), "400.00");
        transferenciaInterna.setCategoryId(childId);
        transferenciaInterna.setInternalTransfer(true);

        when(bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(eq(USER_ID), any(), any()))
                .thenReturn(List.of(porCategoria, porPalavraChave, abaixoDoPiso, ignorada, transferenciaInterna));

        IncomePatternResponse response = service.analyze(EMAIL, HOJE);

        assertThat(response.inferred().purchasesPerMonth()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("savePreference: caminho feliz em dinheiro grava a preferência")
    void savePreferenceHappyPathCash() {
        when(purchasePreferenceRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(purchasePreferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IncomePatternResponse.Preference result =
                service.savePreference(EMAIL, "monthly", null, null, null, null);

        assertThat(result.cadence()).isEqualTo("MONTHLY");
        assertThat(result.weekendPreferred()).isTrue();
        assertThat(result.paymentMode()).isNull();
    }

    @Test
    @DisplayName("savePreference: caminho feliz no cartão exige um cartão de crédito do usuário")
    void savePreferenceHappyPathCard() {
        UUID cardId = UUID.randomUUID();
        ConnectorAccount card = ConnectorAccount.builder().id(cardId)
                .type(ConnectorAccount.AccountType.CREDIT_CARD).build();
        when(connectorAccountRepository.findByIdAndUserId(cardId, USER_ID)).thenReturn(Optional.of(card));
        when(purchasePreferenceRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(purchasePreferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IncomePatternResponse.Preference result =
                service.savePreference(EMAIL, "WEEKLY", false, "card", cardId, "meal_voucher");

        assertThat(result.cadence()).isEqualTo("WEEKLY");
        assertThat(result.weekendPreferred()).isFalse();
        assertThat(result.paymentMode()).isEqualTo("CARD");
        assertThat(result.cardAccountId()).isEqualTo(cardId);
        assertThat(result.fundingKind()).isEqualTo("MEAL_VOUCHER");
    }

    @Test
    @DisplayName("savePreference: cadência inválida responde com IllegalArgumentException")
    void savePreferenceRejectsInvalidCadence() {
        assertThatThrownBy(() -> service.savePreference(EMAIL, "DAILY", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MONTHLY ou WEEKLY");
    }

    @Test
    @DisplayName("savePreference: forma de pagamento inválida responde com IllegalArgumentException")
    void savePreferenceRejectsInvalidPaymentMode() {
        assertThatThrownBy(() -> service.savePreference(EMAIL, "MONTHLY", null, "PIX", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CASH ou CARD");
    }

    @Test
    @DisplayName("savePreference: CARD sem cardAccountId responde com IllegalArgumentException")
    void savePreferenceRejectsCardWithoutAccount() {
        assertThatThrownBy(() -> service.savePreference(EMAIL, "MONTHLY", null, "CARD", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Informe o cartão");
    }

    @Test
    @DisplayName("savePreference: cartão que não é de crédito responde com IllegalArgumentException")
    void savePreferenceRejectsAccountThatIsNotACreditCard() {
        UUID accountId = UUID.randomUUID();
        ConnectorAccount contaCorrente = ConnectorAccount.builder().id(accountId)
                .type(ConnectorAccount.AccountType.BANK).build();
        when(connectorAccountRepository.findByIdAndUserId(accountId, USER_ID)).thenReturn(Optional.of(contaCorrente));

        assertThatThrownBy(() -> service.savePreference(EMAIL, "MONTHLY", null, "CARD", accountId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("não é um cartão de crédito");
    }

    @Test
    @DisplayName("savePreference: fundingKind inválido responde com IllegalArgumentException")
    void savePreferenceRejectsInvalidFundingKind() {
        assertThatThrownBy(() -> service.savePreference(EMAIL, "MONTHLY", null, null, null, "BONUS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Fonte de financiamento inválida");
    }

    @Test
    @DisplayName("clearPreference apaga a preferência do usuário")
    void clearPreferenceDeletesTheStoredPreference() {
        service.clearPreference(EMAIL);

        verify(purchasePreferenceRepository).deleteById(USER_ID);
    }

    // ---------------------------------------------------------------- helpers

    private IncomeSource givenSalarySource(LocalDate... dates) {
        UUID seriesId = UUID.randomUUID();
        IncomeSource salary = IncomeSource.builder().id(UUID.randomUUID()).kind(IncomeSource.Kind.SALARY)
                .name("Salário").confirmed(true).active(true).seriesId(seriesId).build();
        List<UUID> ids = new ArrayList<>();
        List<BankTransaction> txs = new ArrayList<>();
        for (LocalDate d : dates) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            txs.add(credit(id, d, "4400.00", false));
        }
        givenSeriesLinks(seriesId, ids.toArray(new UUID[0]));
        when(bankTransactionRepository.findAllByUserIdAndIdIn(eq(USER_ID), eq(ids))).thenReturn(txs);
        return salary;
    }

    private BankTransaction grocery(LocalDate date, String amount) {
        return BankTransaction.builder().id(UUID.randomUUID()).type("DEBIT")
                .amount(new BigDecimal(amount).negate())
                .date(date.atStartOfDay().atOffset(ZoneOffset.UTC))
                .ignored(false).internalTransfer(false).refunded(false).build();
    }

    private void givenSeriesLinks(UUID seriesId, UUID... txIds) {
        when(linkRepository.findAllBySeriesIdIn(List.of(seriesId))).thenReturn(linksFor(seriesId, txIds));
    }

    private List<RecurringSeriesLink> linksFor(UUID seriesId, UUID... txIds) {
        List<RecurringSeriesLink> links = new ArrayList<>();
        for (UUID txId : txIds) {
            links.add(RecurringSeriesLink.builder().id(UUID.randomUUID()).seriesId(seriesId)
                    .bankTransactionId(txId).build());
        }
        return links;
    }

    private BankTransaction credit(UUID id, LocalDate date, String amount, boolean ignored) {
        return BankTransaction.builder().id(id).type("CREDIT").amount(new BigDecimal(amount))
                .date(date.atStartOfDay().atOffset(ZoneOffset.UTC)).ignored(ignored).build();
    }
}
