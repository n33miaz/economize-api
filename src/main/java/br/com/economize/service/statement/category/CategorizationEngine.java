package br.com.economize.service.statement.category;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.CategoryRule;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.CategoryRuleRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ordem de resolução: regra do usuário (exata > contida, criada > aprendida) →
 * keyword do sistema → fallback por tipo → sem categoria (pede ajuda na revisão).
 * A confiança decresce nessa ordem e é exposta para a UI priorizar a conferência.
 */
@Service
@RequiredArgsConstructor
public class CategorizationEngine {

    private static final BigDecimal CONF_USER_EXACT = new BigDecimal("1.00");
    private static final BigDecimal CONF_LEARNED_EXACT = new BigDecimal("0.95");
    private static final BigDecimal CONF_USER_CONTAINS = new BigDecimal("0.90");
    private static final BigDecimal CONF_LEARNED_CONTAINS = new BigDecimal("0.85");
    private static final BigDecimal CONF_KEYWORD = new BigDecimal("0.70");
    private static final BigDecimal CONF_FALLBACK = new BigDecimal("0.60");

    private final CategoryRuleRepository ruleRepository;
    private final CategoryRepository categoryRepository;
    private final RuleBasedCategorizationService keywordService;

    /**
     * Carrega regras e seeds uma única vez por importação — o motor roda por
     * transação e não pode custar um SELECT cada.
     */
    public Context contextFor(UUID userId) {
        List<CategoryRule> rules = ruleRepository.findAllWithCategoryByUserId(userId);
        Map<String, Category> seeds = categoryRepository.findAllByUserIsNull().stream()
                .filter(c -> c.getSystemKey() != null)
                .collect(Collectors.toMap(Category::getSystemKey, Function.identity(), (a, b) -> a));
        return new Context(rules, seeds);
    }

    public Result categorize(Context ctx, String description, String type) {
        String normalized = DescriptionNormalizer.normalize(description);

        Match match = bestRuleMatch(ctx, normalized);
        if (match != null) {
            match.rule.setHits(match.rule.getHits() + 1);
            match.rule.setLastHitAt(OffsetDateTime.now());
            ctx.dirtyRules.add(match.rule);
            return new Result(match.rule.getCategory(), match.by, match.confidence, normalized);
        }

        // Keywords rodam na descrição crua: elas foram calibradas com os jargões
        // que o normalizador remove (ex.: "pix" para TRANSFER)
        RuleBasedCategorizationService.Hit keyword = keywordService.match(description).orElse(null);
        if (keyword != null) {
            // subcategoria primeiro; o pai é a rede quando o seed da sub não existe
            Category seed = ctx.seedsByKey.get(keyword.systemKey());
            if (seed == null || seed.isArchived()) seed = ctx.seedsByKey.get(keyword.parentKey());
            if (seed != null && !seed.isArchived()) {
                return new Result(seed, BankTransaction.CategorizedBy.KEYWORD, CONF_KEYWORD, normalized);
            }
        }

        if ("CREDIT".equalsIgnoreCase(type)) {
            Category income = ctx.seedsByKey.get(TransactionCategory.INCOME.name());
            if (income != null) {
                return new Result(income, BankTransaction.CategorizedBy.FALLBACK, CONF_FALLBACK, normalized);
            }
        }

        return new Result(null, null, null, normalized);
    }

    private Match bestRuleMatch(Context ctx, String normalized) {
        if (normalized.isBlank()) return null;

        Match best = null;
        for (CategoryRule rule : ctx.rules) {
            if (rule.getCategory() == null || rule.getCategory().isArchived()) continue;
            String pattern = rule.getPattern();
            if (pattern == null || pattern.isBlank()) continue;

            boolean exact = pattern.equals(normalized);
            boolean contains = !exact && rule.getMatchType() == CategoryRule.MatchType.CONTAINS
                    && normalized.contains(pattern);
            if (!exact && !contains) continue;

            boolean userMade = rule.getOrigin() == CategoryRule.Origin.USER;
            BigDecimal confidence = exact
                    ? (userMade ? CONF_USER_EXACT : CONF_LEARNED_EXACT)
                    : (userMade ? CONF_USER_CONTAINS : CONF_LEARNED_CONTAINS);
            Match candidate = new Match(rule,
                    userMade ? BankTransaction.CategorizedBy.USER_RULE : BankTransaction.CategorizedBy.LEARNED_RULE,
                    confidence, exact ? Integer.MAX_VALUE : pattern.length());

            // padrão mais específico (exato > mais longo) vence; empate fica com o primeiro
            if (best == null || candidate.specificity > best.specificity
                    || (candidate.specificity == best.specificity && candidate.confidence.compareTo(best.confidence) > 0)) {
                best = candidate;
            }
        }
        return best;
    }

    public record Result(Category category, BankTransaction.CategorizedBy by,
                         BigDecimal confidence, String normalizedDescription) {

        public boolean resolved() {
            return category != null;
        }
    }

    private record Match(CategoryRule rule, BankTransaction.CategorizedBy by,
                         BigDecimal confidence, int specificity) {
    }

    @Getter
    public static class Context {
        private final List<CategoryRule> rules;
        private final Map<String, Category> seedsByKey;
        // regras que pontuaram nesta importação — o chamador persiste os hits em lote
        private final Set<CategoryRule> dirtyRules = new HashSet<>();

        Context(List<CategoryRule> rules, Map<String, Category> seedsByKey) {
            this.rules = rules;
            this.seedsByKey = new HashMap<>(seedsByKey);
        }
    }
}
