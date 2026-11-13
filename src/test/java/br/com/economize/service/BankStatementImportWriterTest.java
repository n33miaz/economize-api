package br.com.economize.service;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.CategoryRule;
import br.com.economize.model.StatementUpload;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gravação da importação contra um banco de verdade (H2): o acerto da regra é
 * SOMADO ao que está no banco, não sobrescrito pelo que a importação tinha em
 * mãos.
 *
 * <p>O que este teste NÃO cobre: o rollback de uma gravação que falha. O
 * {@code @DataJpaTest} roda tudo numa transação só, então o {@code write} aqui
 * entra na transação do teste em vez de abrir a dele.
 */
@DataJpaTest
@Import(StatementImportWriter.class)
@DisplayName("StatementImportWriter — acertos de regra somados no banco")
class BankStatementImportWriterTest {

    @Autowired
    private StatementImportWriter writer;

    @Autowired
    private BankTransactionRepository bankTransactionRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("Soma o incremento ao total do banco e grava upload e linhas juntos")
    void somaAcertosAoQueEstaNoBanco() {
        User user = em.persist(User.builder().name("Dono").email("dono@economize.test")
                .password("nao-importa").createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
        Category delivery = em.persist(Category.builder().user(user).name("Delivery").slug("delivery")
                .flow(Category.Flow.EXPENSE).archived(false).build());
        CategoryRule regra = em.persist(regra(user, delivery, "ifood", 5));
        CategoryRule outra = em.persist(regra(user, delivery, "rappi", 9));
        em.flush();
        em.clear();
        // a cópia solta que a importação carrega, já velha: outra gravação
        // somou nesse meio-tempo. Salvar o total dela apagaria esses acertos
        em.getEntityManager().createQuery("update CategoryRule r set r.hits = 8 where r.id = :id")
                .setParameter("id", regra.getId()).executeUpdate();

        StatementUpload upload = writer.write(
                StatementUpload.builder().user(user).fileHash("a".repeat(64)).fileName("Nubank")
                        .format("PLUGGY").transactionsImported(1).build(),
                List.of(BankTransaction.builder().user(user).transactionId("PLUGGY-n1").type("DEBIT")
                        .amount(new BigDecimal("-30.00")).description("IFOOD")
                        .date(OffsetDateTime.of(2026, 5, 10, 12, 0, 0, 0, ZoneOffset.UTC))
                        .reviewStatus(BankTransaction.ReviewStatus.SUGGESTED).build()),
                List.of(new StatementImportWriter.RuleHits(regra.getId(), 2)));
        em.flush();
        em.clear();

        CategoryRule lida = em.find(CategoryRule.class, regra.getId());
        assertThat(lida.getHits()).isEqualTo(10);
        assertThat(lida.getLastHitAt()).isNotNull();
        // regra que não decidiu nada nesta gravação não é tocada
        CategoryRule intocada = em.find(CategoryRule.class, outra.getId());
        assertThat(intocada.getHits()).isEqualTo(9);
        assertThat(intocada.getLastHitAt()).isNull();
        assertThat(bankTransactionRepository.findAll()).singleElement()
                .satisfies(tx -> assertThat(tx.getUploadId()).isEqualTo(upload.getId()));
    }

    private CategoryRule regra(User user, Category categoria, String padrao, int acertos) {
        return CategoryRule.builder().user(user).category(categoria).pattern(padrao)
                .matchType(CategoryRule.MatchType.CONTAINS).origin(CategoryRule.Origin.USER)
                .hits(acertos).build();
    }
}
