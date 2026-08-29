package br.com.economize.service.statement.category;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.CategoryRule;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.CategoryRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategorizationEngineTest {

    @Mock
    private CategoryRuleRepository ruleRepository;

    @Mock
    private CategoryRepository categoryRepository;

    private CategorizationEngine engine;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // keyword service concreto: o mapeamento keyword→seed faz parte do contrato do motor
        engine = new CategorizationEngine(ruleRepository, categoryRepository, new RuleBasedCategorizationService());
    }

    @Test
    void userExactRuleBeatsKeywordWithFullConfidence() {
        Category delivery = userCategory("Delivery");
        CategoryRule rule = rule("ifood rest", CategoryRule.MatchType.EXACT, CategoryRule.Origin.USER, delivery);
        CategorizationEngine.Context ctx = context(List.of(rule), seed("FOOD", "Alimentação"));

        // a descrição casaria a keyword "ifood", mas a regra do usuário tem prioridade
        CategorizationEngine.Result result = engine.categorize(ctx, "COMPRA CARTAO IFOOD *REST", "DEBIT");

        assertThat(result.category()).isSameAs(delivery);
        assertThat(result.by()).isEqualTo(BankTransaction.CategorizedBy.USER_RULE);
        assertThat(result.confidence()).isEqualByComparingTo("1.00");
        assertThat(result.normalizedDescription()).isEqualTo("ifood rest");
        assertThat(rule.getHits()).isEqualTo(1);
        assertThat(rule.getLastHitAt()).isNotNull();
        assertThat(ctx.getDirtyRules()).containsExactly(rule);
    }

    @Test
    void learnedExactRuleResolvesWithLowerConfidence() {
        Category groceries = userCategory("Mercearia");
        CategoryRule rule = rule("mercearia do ze", CategoryRule.MatchType.EXACT, CategoryRule.Origin.LEARNED, groceries);
        CategorizationEngine.Context ctx = context(List.of(rule));

        CategorizationEngine.Result result = engine.categorize(ctx, "MERCEARIA DO ZE", "DEBIT");

        assertThat(result.category()).isSameAs(groceries);
        assertThat(result.by()).isEqualTo(BankTransaction.CategorizedBy.LEARNED_RULE);
        assertThat(result.confidence()).isEqualByComparingTo("0.95");
    }

    @Test
    void longerContainsPatternBeatsShorterOne() {
        Category generic = userCategory("Genérica");
        Category specific = userCategory("Específica");
        CategoryRule shortRule = rule("ifood", CategoryRule.MatchType.CONTAINS, CategoryRule.Origin.LEARNED, generic);
        CategoryRule longRule = rule("ifood rest", CategoryRule.MatchType.CONTAINS, CategoryRule.Origin.LEARNED, specific);
        CategorizationEngine.Context ctx = context(List.of(shortRule, longRule));

        CategorizationEngine.Result result = engine.categorize(ctx, "IFOOD REST SP", "DEBIT");

        assertThat(result.category()).isSameAs(specific);
        assertThat(result.by()).isEqualTo(BankTransaction.CategorizedBy.LEARNED_RULE);
        assertThat(result.confidence()).isEqualByComparingTo("0.85");
        assertThat(ctx.getDirtyRules()).containsExactly(longRule);
    }

    @Test
    void ruleOfArchivedCategoryIsIgnored() {
        Category archived = userCategory("Antiga");
        archived.setArchived(true);
        CategoryRule rule = rule("ifood rest", CategoryRule.MatchType.EXACT, CategoryRule.Origin.USER, archived);
        Category food = seed("FOOD", "Alimentação");
        CategorizationEngine.Context ctx = context(List.of(rule), food);

        CategorizationEngine.Result result = engine.categorize(ctx, "IFOOD REST", "DEBIT");

        assertThat(result.category()).isSameAs(food);
        assertThat(result.by()).isEqualTo(BankTransaction.CategorizedBy.KEYWORD);
        assertThat(ctx.getDirtyRules()).isEmpty();
    }

    @Test
    void keywordResolvesToSystemSeedWhenNoRuleMatches() {
        Category food = seed("FOOD", "Alimentação");
        CategorizationEngine.Context ctx = context(List.of(), food);

        CategorizationEngine.Result result = engine.categorize(ctx, "IFOOD *REST 4321", "DEBIT");

        assertThat(result.category()).isSameAs(food);
        assertThat(result.by()).isEqualTo(BankTransaction.CategorizedBy.KEYWORD);
        assertThat(result.confidence()).isEqualByComparingTo("0.70");
        assertThat(result.normalizedDescription()).isEqualTo("ifood rest");
    }

    @Test
    void creditWithoutKeywordFallsBackToIncomeSeed() {
        Category income = seed("INCOME", "Receitas");
        CategorizationEngine.Context ctx = context(List.of(), income);

        CategorizationEngine.Result result = engine.categorize(ctx, "SALDO REMANESCENTE", "CREDIT");

        assertThat(result.category()).isSameAs(income);
        assertThat(result.by()).isEqualTo(BankTransaction.CategorizedBy.FALLBACK);
        assertThat(result.confidence()).isEqualByComparingTo("0.60");
    }

    @Test
    void unresolvedResultStillCarriesNormalizedDescription() {
        CategorizationEngine.Context ctx = context(List.of(), seed("INCOME", "Receitas"));

        CategorizationEngine.Result result = engine.categorize(ctx, "SALDO REMANESCENTE", "DEBIT");

        assertThat(result.resolved()).isFalse();
        assertThat(result.category()).isNull();
        assertThat(result.by()).isNull();
        assertThat(result.confidence()).isNull();
        assertThat(result.normalizedDescription()).isEqualTo("saldo remanescente");
    }

    @Test
    @DisplayName("EC-113: crédito que é PERNA INTERNA cai em Transferências, nunca em Receitas")
    void internalTransferLegNeverFallsBackToIncome() {
        Category income = seed("INCOME", "Receitas");
        Category transfer = seed("TRANSFER", "Transferências");
        CategorizationEngine.Context ctx = context(List.of(), income, transfer);

        // crédito dentro de um cartão: quita fatura ou estorna compra, nunca é
        // renda. Antes disto ele virava Receita, o usuário aprovava na revisão e
        // o motor APRENDIA "isto é Receita" — contaminando as próximas importações
        CategorizationEngine.Result result =
                engine.categorize(ctx, "CREDITO EM CONTA", "CREDIT", true);

        assertThat(result.category()).isSameAs(transfer);
        assertThat(result.by()).isEqualTo(BankTransaction.CategorizedBy.FALLBACK);
        assertThat(result.confidence()).isEqualByComparingTo("0.60");
    }

    @Test
    @DisplayName("EC-113: sem o seed de Transferências, a perna interna vai para a revisão — não para Receitas")
    void internalTransferLegPrefersReviewQueueOverWrongIncome() {
        CategorizationEngine.Context ctx = context(List.of(), seed("INCOME", "Receitas"));

        CategorizationEngine.Result result =
                engine.categorize(ctx, "CREDITO EM CONTA", "CREDIT", true);

        assertThat(result.resolved()).isFalse();
        assertThat(result.normalizedDescription()).isEqualTo("credito em conta");
    }

    @Test
    @DisplayName("EC-113: crédito interno COM keyword de receita ainda é Transferências — o caso que faltava")
    void internalTransferLegBeatsIncomeKeyword() {
        Category income = seed("INCOME", "Receitas");
        Category cashback = seed("INCOME_CASHBACK", "Cashback");
        Category fees = seed("FEES_BANK", "Tarifas bancárias");
        Category transfer = seed("TRANSFER", "Transferências");
        CategorizationEngine.Context ctx = context(List.of(), income, cashback, fees, transfer);

        // "estorno", "reembolso", "cashback", "devolução" e "rendimento" são
        // keywords de INCOME: como último degrau, a marca de perna interna nunca
        // era alcançada por um crédito de cartão com qualquer uma delas
        assertThat(engine.categorize(ctx, "ESTORNO DE COMPRA", "CREDIT", true).category())
                .isSameAs(transfer);
        assertThat(engine.categorize(ctx, "CREDITO ESTORNO ANUIDADE", "CREDIT", true).category())
                .isSameAs(transfer);
        assertThat(engine.categorize(ctx, "REEMBOLSO CASHBACK NUBANK", "CREDIT", true).category())
                .isSameAs(transfer);

        // e a MESMA descrição, quando não é perna interna, continua caindo onde
        // sempre caiu: o curto-circuito é do fato, não do texto
        assertThat(engine.categorize(ctx, "ESTORNO DE COMPRA", "CREDIT", false).category())
                .isSameAs(cashback);
        assertThat(engine.categorize(ctx, "CREDITO ESTORNO ANUIDADE", "CREDIT", false).category())
                .isSameAs(fees);
    }

    @Test
    @DisplayName("EC-113: regra APRENDIDA apontando para Receitas não vence a perna interna")
    void internalTransferLegBeatsLearnedRulePointingToIncome() {
        Category income = seed("INCOME", "Receitas");
        Category transfer = seed("TRANSFER", "Transferências");
        // exatamente a regra que a contaminação anterior gravava: o usuário
        // aprovou "pagamento efetuado" como Receita antes desta correção existir
        CategoryRule contaminada = rule("pagamento efetuado",
                CategoryRule.MatchType.EXACT, CategoryRule.Origin.LEARNED, income);
        CategorizationEngine.Context ctx = context(List.of(contaminada), income, transfer);

        CategorizationEngine.Result result =
                engine.categorize(ctx, "PAGAMENTO EFETUADO", "CREDIT", true);

        // a regra aprendida roda PRIMEIRO na cadeia normal e venceria para
        // sempre; com o curto-circuito ela nem é consultada
        assertThat(result.category()).isSameAs(transfer);
        assertThat(result.by()).isEqualTo(BankTransaction.CategorizedBy.FALLBACK);
        assertThat(contaminada.getHits()).isZero();
        assertThat(ctx.getDirtyRules()).isEmpty();
    }

    @Test
    @DisplayName("EC-113: nem regra CRIADA À MÃO vence a perna interna — a marca é fato, não palpite")
    void internalTransferLegBeatsUserMadeRule() {
        Category transfer = seed("TRANSFER", "Transferências");
        Category minhaReceita = userCategory("Minha receita");
        CategoryRule doUsuario = rule("estorno de compra",
                CategoryRule.MatchType.EXACT, CategoryRule.Origin.USER, minhaReceita);
        CategorizationEngine.Context ctx = context(List.of(doUsuario), transfer);

        assertThat(engine.categorize(ctx, "ESTORNO DE COMPRA", "CREDIT", true).category())
                .isSameAs(transfer);
        // e a mesma regra continua mandando na linha que NÃO é perna interna
        assertThat(engine.categorize(ctx, "ESTORNO DE COMPRA", "CREDIT", false).category())
                .isSameAs(minhaReceita);
    }

    @Test
    @DisplayName("a sobrecarga de 3 argumentos segue valendo: linha comum não é perna interna")
    void threeArgOverloadKeepsTheIncomeFallback() {
        Category income = seed("INCOME", "Receitas");
        CategorizationEngine.Context ctx = context(List.of(), income, seed("TRANSFER", "Transferências"));

        assertThat(engine.categorize(ctx, "SALDO REMANESCENTE", "CREDIT").category()).isSameAs(income);
    }

    private CategorizationEngine.Context context(List<CategoryRule> rules, Category... seeds) {
        when(ruleRepository.findAllWithCategoryByUserId(userId)).thenReturn(rules);
        when(categoryRepository.findAllByUserIsNull()).thenReturn(List.of(seeds));
        return engine.contextFor(userId);
    }

    private Category seed(String systemKey, String name) {
        return Category.builder()
                .id(UUID.randomUUID())
                .name(name)
                .slug(systemKey.toLowerCase())
                .systemKey(systemKey)
                .flow(Category.Flow.EXPENSE)
                .archived(false)
                .build();
    }

    private Category userCategory(String name) {
        return Category.builder()
                .id(UUID.randomUUID())
                .name(name)
                .slug(name.toLowerCase())
                .flow(Category.Flow.EXPENSE)
                .archived(false)
                .build();
    }

    private CategoryRule rule(String pattern, CategoryRule.MatchType matchType,
                              CategoryRule.Origin origin, Category category) {
        return CategoryRule.builder()
                .id(UUID.randomUUID())
                .category(category)
                .pattern(pattern)
                .matchType(matchType)
                .origin(origin)
                .hits(0)
                .build();
    }
}
