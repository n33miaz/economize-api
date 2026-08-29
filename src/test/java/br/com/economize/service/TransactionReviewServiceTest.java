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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionReviewServiceTest {

    private static final String EMAIL = "ana@economize.dev";

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private CategoryRuleRepository categoryRuleRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private TransactionReviewService service;

    private final User user = User.builder()
            .id(UUID.randomUUID()).name("Ana").email(EMAIL).password("x").build();

    private final Category food = Category.builder()
            .id(UUID.randomUUID()).name("Alimentação").slug("alimentacao")
            .systemKey("FOOD").flow(Category.Flow.EXPENSE).archived(false).build();

    @Test
    void applyConfirmsTransactionsAndLearnsExactRule() {
        BankTransaction tx1 = pendingTx("ifood rest");
        BankTransaction tx2 = pendingTx("ifood rest");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(categoryRepository.findAccessible(food.getId(), user.getId())).thenReturn(Optional.of(food));
        when(bankTransactionRepository.findAllByUserIdAndIdIn(user.getId(), List.of(tx1.getId(), tx2.getId())))
                .thenReturn(List.of(tx1, tx2));
        when(categoryRuleRepository.findByUserIdAndPattern(user.getId(), "ifood rest")).thenReturn(Optional.empty());

        TransactionReviewService.ReviewOutcome outcome =
                service.apply(EMAIL, request(List.of(tx1.getId(), tx2.getId()), food.getId(), null));

        assertThat(outcome.confirmed()).isEqualTo(2);
        assertThat(outcome.rulesSaved()).isEqualTo(1);
        for (BankTransaction tx : List.of(tx1, tx2)) {
            assertThat(tx.getReviewStatus()).isEqualTo(BankTransaction.ReviewStatus.CONFIRMED);
            assertThat(tx.getCategorizedBy()).isEqualTo(BankTransaction.CategorizedBy.USER);
            assertThat(tx.getConfidence()).isNull();
            assertThat(tx.getCategoryId()).isEqualTo(food.getId());
            assertThat(tx.getCategory()).isEqualTo("FOOD");
        }
        verify(bankTransactionRepository).saveAll(List.of(tx1, tx2));

        ArgumentCaptor<CategoryRule> captor = ArgumentCaptor.forClass(CategoryRule.class);
        verify(categoryRuleRepository).save(captor.capture());
        CategoryRule learned = captor.getValue();
        assertThat(learned.getPattern()).isEqualTo("ifood rest");
        assertThat(learned.getMatchType()).isEqualTo(CategoryRule.MatchType.EXACT);
        assertThat(learned.getOrigin()).isEqualTo(CategoryRule.Origin.LEARNED);
        assertThat(learned.getHits()).isEqualTo(1);
        assertThat(learned.getLastHitAt()).isNotNull();
        assertThat(learned.getCategory()).isSameAs(food);
        assertThat(learned.getUser()).isSameAs(user);
    }

    @Test
    void applyWithLearnPatternFalseDoesNotCreateRule() {
        BankTransaction tx = pendingTx("ifood rest");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(categoryRepository.findAccessible(food.getId(), user.getId())).thenReturn(Optional.of(food));
        when(bankTransactionRepository.findAllByUserIdAndIdIn(user.getId(), List.of(tx.getId())))
                .thenReturn(List.of(tx));

        TransactionReviewService.ReviewOutcome outcome =
                service.apply(EMAIL, request(List.of(tx.getId()), food.getId(), Boolean.FALSE));

        assertThat(outcome.confirmed()).isEqualTo(1);
        assertThat(outcome.rulesSaved()).isZero();
        assertThat(tx.getReviewStatus()).isEqualTo(BankTransaction.ReviewStatus.CONFIRMED);
        verifyNoInteractions(categoryRuleRepository);
    }

    @Test
    void applyReinforcesExistingRuleInsteadOfCreatingAnother() {
        Category transport = Category.builder()
                .id(UUID.randomUUID()).name("Transporte").slug("transporte")
                .systemKey("TRANSPORT").flow(Category.Flow.EXPENSE).archived(false).build();
        CategoryRule existing = CategoryRule.builder()
                .id(UUID.randomUUID()).user(user).category(transport)
                .pattern("ifood rest").matchType(CategoryRule.MatchType.EXACT)
                .origin(CategoryRule.Origin.LEARNED).hits(3).build();
        BankTransaction tx = pendingTx("ifood rest");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(categoryRepository.findAccessible(food.getId(), user.getId())).thenReturn(Optional.of(food));
        when(bankTransactionRepository.findAllByUserIdAndIdIn(user.getId(), List.of(tx.getId())))
                .thenReturn(List.of(tx));
        when(categoryRuleRepository.findByUserIdAndPattern(user.getId(), "ifood rest"))
                .thenReturn(Optional.of(existing));

        service.apply(EMAIL, request(List.of(tx.getId()), food.getId(), null));

        // o usuário corrigiu a categoria: a regra migra para a nova e ganha reforço
        assertThat(existing.getCategory()).isSameAs(food);
        assertThat(existing.getHits()).isEqualTo(4);
        assertThat(existing.getLastHitAt()).isNotNull();
        verify(categoryRuleRepository).save(same(existing));
        verify(categoryRuleRepository, times(1)).save(any(CategoryRule.class));
    }

    @Test
    void applyThrowsWhenCategoryIsNotAccessible() {
        UUID foreignCategoryId = UUID.randomUUID();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(categoryRepository.findAccessible(foreignCategoryId, user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(EMAIL,
                request(List.of(UUID.randomUUID()), foreignCategoryId, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Categoria não encontrada");
        verify(bankTransactionRepository, never()).saveAll(any());
    }

    @Test
    void confirmAllPromotesOnlySuggestedWithCategoryAndLearnsPatterns() {
        BankTransaction suggested = pendingTx("ifood rest");
        suggested.setCategoryId(food.getId());
        BankTransaction uncategorized = pendingTx("loja x");
        uncategorized.setReviewStatus(BankTransaction.ReviewStatus.UNCATEGORIZED);
        BankTransaction suggestedWithoutCategory = pendingTx("padaria da esquina");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.findAllByUserIdAndReviewStatusInOrderByDateDesc(eq(user.getId()), anyCollection()))
                .thenReturn(List.of(suggested, uncategorized, suggestedWithoutCategory));
        when(categoryRepository.findAccessible(food.getId(), user.getId())).thenReturn(Optional.of(food));
        when(categoryRuleRepository.findByUserIdAndPattern(user.getId(), "ifood rest")).thenReturn(Optional.empty());

        TransactionReviewService.ReviewOutcome outcome = service.confirmAll(EMAIL, null);

        assertThat(outcome.confirmed()).isEqualTo(1);
        assertThat(outcome.rulesSaved()).isEqualTo(1);
        assertThat(suggested.getReviewStatus()).isEqualTo(BankTransaction.ReviewStatus.CONFIRMED);
        assertThat(uncategorized.getReviewStatus()).isEqualTo(BankTransaction.ReviewStatus.UNCATEGORIZED);
        assertThat(suggestedWithoutCategory.getReviewStatus()).isEqualTo(BankTransaction.ReviewStatus.SUGGESTED);
        verify(bankTransactionRepository).saveAll(List.of(suggested));

        ArgumentCaptor<CategoryRule> captor = ArgumentCaptor.forClass(CategoryRule.class);
        verify(categoryRuleRepository).save(captor.capture());
        assertThat(captor.getValue().getPattern()).isEqualTo("ifood rest");
        assertThat(captor.getValue().getOrigin()).isEqualTo(CategoryRule.Origin.LEARNED);
    }

    @Test
    @DisplayName("EC-113: aprovar uma PERNA INTERNA confirma a linha mas NÃO grava regra aprendida")
    void applyDoesNotLearnFromInternalTransferLeg() {
        BankTransaction perna = pendingTx("pagamento efetuado");
        perna.setInternalTransfer(true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(categoryRepository.findAccessible(food.getId(), user.getId())).thenReturn(Optional.of(food));
        when(bankTransactionRepository.findAllByUserIdAndIdIn(user.getId(), List.of(perna.getId())))
                .thenReturn(List.of(perna));

        TransactionReviewService.ReviewOutcome outcome =
                service.apply(EMAIL, request(List.of(perna.getId()), food.getId(), null));

        // a decisão do usuário sobre a linha vale; o que não pode é virar regra
        // — "pagamento efetuado" se repete em linhas que não são perna interna,
        // e a regra aprendida roda antes de qualquer keyword
        assertThat(outcome.confirmed()).isEqualTo(1);
        assertThat(outcome.rulesSaved()).isZero();
        assertThat(perna.getReviewStatus()).isEqualTo(BankTransaction.ReviewStatus.CONFIRMED);
        verifyNoInteractions(categoryRuleRepository);
    }

    @Test
    @DisplayName("EC-113: a confirmação em lote também não aprende com perna interna")
    void confirmAllDoesNotLearnFromInternalTransferLeg() {
        BankTransaction perna = pendingTx("estorno de compra");
        perna.setInternalTransfer(true);
        perna.setCategoryId(food.getId());
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.findAllByUserIdAndReviewStatusInOrderByDateDesc(eq(user.getId()), anyCollection()))
                .thenReturn(List.of(perna));
        when(categoryRepository.findAccessible(food.getId(), user.getId())).thenReturn(Optional.of(food));

        TransactionReviewService.ReviewOutcome outcome = service.confirmAll(EMAIL, null);

        assertThat(outcome.confirmed()).isEqualTo(1);
        assertThat(outcome.rulesSaved()).isZero();
        assertThat(perna.getReviewStatus()).isEqualTo(BankTransaction.ReviewStatus.CONFIRMED);
        verifyNoInteractions(categoryRuleRepository);
    }

    @Test
    void legacyKeyPrefersSystemKey() {
        assertThat(TransactionReviewService.legacyKey(food)).isEqualTo("FOOD");
    }

    @Test
    void legacyKeyFallsBackToSlugTruncatedAt32() {
        Category custom = Category.builder()
                .id(UUID.randomUUID()).name("Personalizada").slug("a".repeat(40))
                .flow(Category.Flow.EXPENSE).archived(false).build();

        assertThat(TransactionReviewService.legacyKey(custom)).isEqualTo("a".repeat(32));
    }

    private ReviewApplyRequest request(List<UUID> transactionIds, UUID categoryId, Boolean learnPattern) {
        return new ReviewApplyRequest(List.of(
                new ReviewApplyRequest.Item(transactionIds, categoryId, learnPattern)));
    }

    private BankTransaction pendingTx(String normalized) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .transactionId(UUID.randomUUID().toString())
                .type("DEBIT")
                .amount(new BigDecimal("-25.00"))
                .description("COMPRA CARTAO " + normalized.toUpperCase())
                .normalizedDescription(normalized)
                .reviewStatus(BankTransaction.ReviewStatus.SUGGESTED)
                .categorizedBy(BankTransaction.CategorizedBy.KEYWORD)
                .confidence(new BigDecimal("0.70"))
                .date(OffsetDateTime.now())
                .build();
    }
}
