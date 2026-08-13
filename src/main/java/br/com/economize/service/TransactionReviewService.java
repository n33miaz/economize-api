package br.com.economize.service;

import br.com.economize.dto.statement.ReviewApplyRequest;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.CategoryRule;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.CategoryRuleRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O elo do aprendizado: toda decisão do usuário (aprovar sugestão ou corrigir
 * categoria) promove a transação a CONFIRMED e grava/reforça uma regra EXACT com
 * a descrição normalizada — a próxima importação do mesmo estabelecimento resolve
 * sozinha, com confiança maior que a das keywords.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionReviewService {

    private static final EnumSet<BankTransaction.ReviewStatus> PENDING =
            EnumSet.of(BankTransaction.ReviewStatus.SUGGESTED, BankTransaction.ReviewStatus.UNCATEGORIZED);

    private final BankTransactionRepository bankTransactionRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final UserRepository userRepository;

    public List<BankTransaction> listTransactions(String email, YearMonth month,
                                                  BankTransaction.ReviewStatus status, UUID categoryId) {
        User user = requireUser(email);
        List<BankTransaction> base;
        if (month != null) {
            OffsetDateTime start = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
            base = bankTransactionRepository
                    .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                            user.getId(), start, start.plusMonths(1));
        } else {
            base = bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId());
        }
        // filtros residuais em memória: volume mensal de finanças pessoais é pequeno
        return base.stream()
                .filter(t -> status == null || t.getReviewStatus() == status)
                .filter(t -> categoryId == null || categoryId.equals(t.getCategoryId()))
                .toList();
    }

    public List<BankTransaction> reviewQueue(String email, UUID uploadId) {
        User user = requireUser(email);
        if (uploadId != null) {
            return bankTransactionRepository
                    .findAllByUserIdAndUploadIdOrderByDateDesc(user.getId(), uploadId).stream()
                    .filter(t -> PENDING.contains(t.getReviewStatus()))
                    .toList();
        }
        return bankTransactionRepository.findAllByUserIdAndReviewStatusInOrderByDateDesc(user.getId(), PENDING);
    }

    public long pendingCount(String email) {
        User user = requireUser(email);
        return bankTransactionRepository.countByUserIdAndReviewStatusIn(user.getId(), PENDING);
    }

    /**
     * Aplica decisões em lote. Cada item confirma um grupo de transações numa
     * categoria; learnPattern (default true) grava o padrão para o futuro.
     */
    public ReviewOutcome apply(String email, ReviewApplyRequest request) {
        User user = requireUser(email);
        int confirmed = 0;
        int rulesSaved = 0;

        for (ReviewApplyRequest.Item item : request.items()) {
            Category category = categoryRepository.findAccessible(item.categoryId(), user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Categoria não encontrada"));
            List<BankTransaction> txs =
                    bankTransactionRepository.findAllByUserIdAndIdIn(user.getId(), item.transactionIds());
            if (txs.isEmpty()) continue;

            Map<String, Category> toLearn = new HashMap<>();
            for (BankTransaction tx : txs) {
                assign(tx, category, BankTransaction.CategorizedBy.USER);
                if (!Boolean.FALSE.equals(item.learnPattern())
                        && tx.getNormalizedDescription() != null && !tx.getNormalizedDescription().isBlank()) {
                    toLearn.put(tx.getNormalizedDescription(), category);
                }
            }
            bankTransactionRepository.saveAll(txs);
            confirmed += txs.size();
            for (Map.Entry<String, Category> entry : toLearn.entrySet()) {
                upsertLearnedRule(user, entry.getKey(), entry.getValue());
                rulesSaved++;
            }
        }
        log.info("Revisão aplicada: {} transações confirmadas, {} padrões salvos, user={}", confirmed, rulesSaved, email);
        return new ReviewOutcome(confirmed, rulesSaved);
    }

    /**
     * Aprova de uma vez tudo que o motor sugeriu (não toca as sem categoria).
     * A aprovação também cristaliza os padrões — é o caminho rápido do usuário
     * que confere a lista e concorda com tudo.
     */
    public ReviewOutcome confirmAll(String email, UUID uploadId) {
        User user = requireUser(email);
        List<BankTransaction> pending = reviewQueue(email, uploadId).stream()
                .filter(t -> t.getReviewStatus() == BankTransaction.ReviewStatus.SUGGESTED
                        && t.getCategoryId() != null)
                .toList();
        if (pending.isEmpty()) return new ReviewOutcome(0, 0);

        Map<UUID, Category> categories = new HashMap<>();
        Map<String, Category> toLearn = new HashMap<>();
        for (BankTransaction tx : pending) {
            Category category = categories.computeIfAbsent(tx.getCategoryId(),
                    id -> categoryRepository.findAccessible(id, user.getId()).orElse(null));
            if (category == null) continue;
            tx.setReviewStatus(BankTransaction.ReviewStatus.CONFIRMED);
            if (tx.getNormalizedDescription() != null && !tx.getNormalizedDescription().isBlank()) {
                toLearn.put(tx.getNormalizedDescription(), category);
            }
        }
        bankTransactionRepository.saveAll(pending);
        int rulesSaved = 0;
        for (Map.Entry<String, Category> entry : toLearn.entrySet()) {
            upsertLearnedRule(user, entry.getKey(), entry.getValue());
            rulesSaved++;
        }
        log.info("Confirmação em lote: {} transações, {} padrões, user={}", pending.size(), rulesSaved, email);
        return new ReviewOutcome(pending.size(), rulesSaved);
    }

    private void assign(BankTransaction tx, Category category, BankTransaction.CategorizedBy by) {
        tx.setCategoryId(category.getId());
        tx.setCategory(legacyKey(category));
        tx.setReviewStatus(BankTransaction.ReviewStatus.CONFIRMED);
        tx.setCategorizedBy(by);
        tx.setConfidence(null);
    }

    private void upsertLearnedRule(User user, String pattern, Category category) {
        CategoryRule rule = categoryRuleRepository.findByUserIdAndPattern(user.getId(), pattern)
                .orElse(null);
        if (rule == null) {
            // EXACT de propósito: o padrão é a chave normalizada inteira, então não
            // há risco de uma regra aprendida "vazar" para estabelecimentos parecidos
            categoryRuleRepository.save(CategoryRule.builder()
                    .user(user)
                    .category(category)
                    .pattern(pattern)
                    .matchType(CategoryRule.MatchType.EXACT)
                    .origin(CategoryRule.Origin.LEARNED)
                    .hits(1)
                    .lastHitAt(OffsetDateTime.now())
                    .build());
            return;
        }
        rule.setCategory(category);
        rule.setHits(rule.getHits() + 1);
        rule.setLastHitAt(OffsetDateTime.now());
        categoryRuleRepository.save(rule);
    }

    /**
     * Mantém a coluna legada em sincronia: relatórios ainda agregam pela string.
     */
    static String legacyKey(Category category) {
        String key = category.getSystemKey() != null ? category.getSystemKey() : category.getSlug();
        return key.length() > 32 ? key.substring(0, 32) : key;
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }

    public record ReviewOutcome(int confirmed, int rulesSaved) {
    }
}
