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
import br.com.economize.service.statement.category.AiCategorySuggester;
import br.com.economize.service.statement.category.CategorizationEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * O reforço da regra aprendida contra um banco de verdade (H2), com as
 * gravações concorrentes encaixadas na janela entre a leitura e a escrita da
 * revisão.
 *
 * <p>O defeito: a revisão lia a regra, somava 1 na cópia e salvava a entidade
 * inteira. Uma sincronização que somasse acertos ({@code addHits}) nesse
 * meio-tempo era apagada pelo total velho + 1.
 *
 * <p>O repositório é o de verdade embrulhado num dublê que só DELEGA, exceto na
 * leitura: é ali que a "outra gravação" acontece, já confirmada no banco, depois
 * que a revisão leu e antes que ela escreva.
 */
@DataJpaTest
// SEM transação de teste: cada chamada ao repositório confirma sozinha, como no
// serviço em produção. É o que deixa a violação do unique estourar no insert e
// a revisão seguir com o UPDATE, em vez de envenenar a transação do teste
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Regra aprendida — reforço atômico contra o banco")
class LearnedRulePersistenceTest {

    @Autowired
    private CategoryRuleRepository realRules;
    @Autowired
    private BankTransactionRepository bankTransactionRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private UserRepository userRepository;

    private CategoryRuleRepository rules;
    private TransactionReviewService service;
    private User user;
    private Category delivery;
    private Category mercado;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder().name("Dono")
                .email(UUID.randomUUID() + "@economize.test").password("x")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
        delivery = categoryRepository.save(Category.builder().user(user).name("Delivery").slug("delivery")
                .flow(Category.Flow.EXPENSE).archived(false).build());
        mercado = categoryRepository.save(Category.builder().user(user).name("Mercado").slug("mercado")
                .flow(Category.Flow.EXPENSE).archived(false).build());

        rules = mock(CategoryRuleRepository.class, AdditionalAnswers.delegatesTo(realRules));
        @SuppressWarnings("unchecked")
        ObjectProvider<AiCategorySuggester> ai = mock(ObjectProvider.class);
        service = new TransactionReviewService(bankTransactionRepository, categoryRepository, rules,
                userRepository, mock(CategorizationEngine.class), ai);
    }

    @AfterEach
    void tearDown() {
        realRules.deleteAll();
        bankTransactionRepository.deleteAll();
        categoryRepository.deleteAll(List.of(delivery, mercado));
        userRepository.delete(user);
    }

    @Test
    @DisplayName("Sincronização soma +3 entre a leitura e a escrita da revisão: 5 + 3 + 1 = 9")
    void revisaoNaoApagaAcertosDaSincronizacao() {
        CategoryRule regra = realRules.save(regra("ifood", delivery, 5));
        AtomicBoolean sincronizou = new AtomicBoolean();
        doAnswer(inv -> {
            Object lida = realRules.findByUserIdAndPattern(inv.getArgument(0), inv.getArgument(1));
            // a sincronização grava DEPOIS da leitura da revisão, uma vez só
            if (sincronizou.compareAndSet(false, true)) {
                realRules.addHits(regra.getId(), 3, OffsetDateTime.now(ZoneOffset.UTC));
            }
            return lida;
        }).when(rules).findByUserIdAndPattern(any(), any());

        service.apply(user.getEmail(), revisar(linha("ifood"), mercado));

        CategoryRule lida = realRules.findById(regra.getId()).orElseThrow();
        assertThat(sincronizou).isTrue();
        assertThat(lida.getHits()).isEqualTo(9);
        // a revisão trocou a categoria e carimbou o acerto
        assertThat(lida.getCategory().getId()).isEqualTo(mercado.getId());
        assertThat(lida.getLastHitAt()).isNotNull();
        assertThat(lida.getUpdatedAt()).isAfterOrEqualTo(lida.getCreatedAt());
        // o que a revisão não decide fica como estava
        assertThat(lida.getOrigin()).isEqualTo(CategoryRule.Origin.USER);
        assertThat(lida.getMatchType()).isEqualTo(CategoryRule.MatchType.CONTAINS);
    }

    @Test
    @DisplayName("Padrão novo vira regra EXACT aprendida com 1 acerto")
    void padraoNovoCriaRegra() {
        service.apply(user.getEmail(), revisar(linha("padaria pao quente"), mercado));

        assertThat(realRules.findAll()).singleElement().satisfies(r -> {
            assertThat(r.getPattern()).isEqualTo("padaria pao quente");
            assertThat(r.getHits()).isEqualTo(1);
            assertThat(r.getOrigin()).isEqualTo(CategoryRule.Origin.LEARNED);
            assertThat(r.getMatchType()).isEqualTo(CategoryRule.MatchType.EXACT);
        });
    }

    @Test
    @DisplayName("Duas revisões do mesmo padrão novo: uma regra só, com os dois acertos")
    void duasInsercoesDoMesmoPadrao() {
        // a outra revisão inseriu a regra depois que esta leu "não existe"
        AtomicBoolean primeiraLeitura = new AtomicBoolean(true);
        doAnswer(inv -> {
            if (primeiraLeitura.getAndSet(false)) {
                realRules.save(CategoryRule.builder().user(user).category(delivery).pattern("ifood")
                        .matchType(CategoryRule.MatchType.EXACT).origin(CategoryRule.Origin.LEARNED)
                        .hits(1).lastHitAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
                return Optional.empty();
            }
            return realRules.findByUserIdAndPattern(inv.getArgument(0), inv.getArgument(1));
        }).when(rules).findByUserIdAndPattern(any(), any());

        service.apply(user.getEmail(), revisar(linha("ifood"), mercado));

        assertThat(realRules.findAll()).singleElement().satisfies(r -> {
            assertThat(r.getHits()).isEqualTo(2);
            // vale a última decisão, como no reforço comum
            assertThat(r.getCategory().getId()).isEqualTo(mercado.getId());
        });
    }

    private CategoryRule regra(String padrao, Category categoria, int acertos) {
        return CategoryRule.builder().user(user).category(categoria).pattern(padrao)
                .matchType(CategoryRule.MatchType.CONTAINS).origin(CategoryRule.Origin.USER)
                .hits(acertos).build();
    }

    private BankTransaction linha(String normalizada) {
        return bankTransactionRepository.save(BankTransaction.builder().user(user)
                .transactionId(UUID.randomUUID().toString()).type("DEBIT")
                .amount(new BigDecimal("-30.00")).description(normalizada.toUpperCase())
                .normalizedDescription(normalizada)
                .date(OffsetDateTime.of(2026, 5, 10, 12, 0, 0, 0, ZoneOffset.UTC))
                .reviewStatus(BankTransaction.ReviewStatus.SUGGESTED).build());
    }

    private ReviewApplyRequest revisar(BankTransaction tx, Category categoria) {
        return new ReviewApplyRequest(List.of(
                new ReviewApplyRequest.Item(List.of(tx.getId()), categoria.getId(), null)));
    }
}
