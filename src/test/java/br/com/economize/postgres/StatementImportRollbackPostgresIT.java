package br.com.economize.postgres;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.CategoryRule;
import br.com.economize.model.StatementUpload;
import br.com.economize.model.User;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.CategoryRuleRepository;
import br.com.economize.service.StatementImportWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rollback da gravação de uma fonte que falha (PR #112).
 *
 * <p>O {@code BankStatementImportWriterTest} declara o que não consegue provar:
 * em {@code @DataJpaTest} o {@code write} entra na transação do teste, então
 * nunca há rollback dele. Aqui o teste não tem transação: o {@code write} abre
 * e confirma (ou desfaz) a sua, contra o schema real.
 *
 * <p>A falha é provocada como acontece de fato: duas linhas do mesmo lote com
 * o mesmo {@code transaction_id}, o que fere {@code unique_transaction_per_user}.
 * Os ids são UUID gerados na aplicação, então os INSERTs só saem no flush do
 * commit — DEPOIS do {@code addHits}, que é um UPDATE direto. É o pior caso:
 * o acerto da regra já foi escrito na transação quando o lote estoura, e só o
 * rollback o tira de lá.
 */
@DisplayName("Postgres: gravação de importação — fonte que falha não deixa rastro")
class StatementImportRollbackPostgresIT extends PostgresIntegrationBase {

    @Autowired
    private StatementImportWriter writer;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CategoryRuleRepository categoryRuleRepository;

    @Test
    @DisplayName("A fonte que estoura no meio sai inteira; a gravada antes continua")
    void fonteQueFalhaNaoDeixaNada() {
        User user = novoUsuario();
        UUID regraId = tx.execute(s -> {
            Category delivery = categoryRepository.save(Category.builder().user(user)
                    .name("Delivery").slug("delivery").flow(Category.Flow.EXPENSE)
                    .archived(false).build());
            return categoryRuleRepository.save(CategoryRule.builder().user(user)
                    .category(delivery).pattern("ifood")
                    .matchType(CategoryRule.MatchType.CONTAINS)
                    .origin(CategoryRule.Origin.USER).hits(5).build()).getId();
        });

        // Fonte A: grava e confirma normalmente
        StatementUpload a = writer.write(upload(user, "a", "Nubank"),
                List.of(linha(user, "PLUGGY-a1", "-30.00")),
                List.of(new StatementImportWriter.RuleHits(regraId, 1)));

        // Fonte B: o lote tem duas linhas com o mesmo transaction_id
        assertThatThrownBy(() -> writer.write(upload(user, "b", "Inter"),
                List.of(linha(user, "PLUGGY-dup", "-10.00"),
                        linha(user, "PLUGGY-dup", "-20.00")),
                List.of(new StatementImportWriter.RuleHits(regraId, 2))))
                .isInstanceOf(RuntimeException.class);

        // Nada da fonte B: nem o upload, nem as linhas, nem o acerto da regra
        assertThat(jdbc.queryForList(
                "select file_name from statement_uploads where user_id = ?", String.class, user.getId()))
                .containsExactly("Nubank");
        assertThat(jdbc.queryForList(
                "select transaction_id from bank_transactions where user_id = ?", String.class, user.getId()))
                .containsExactly("PLUGGY-a1");
        // 5 de antes + 1 da fonte A; os 2 da fonte B voltaram com o rollback
        assertThat(jdbc.queryForObject(
                "select hits from category_rules where id = ?", Integer.class, regraId))
                .isEqualTo(6);
        // e a fonte A continua amarrada ao próprio upload
        assertThat(jdbc.queryForObject(
                "select upload_id from bank_transactions where user_id = ?", UUID.class, user.getId()))
                .isEqualTo(a.getId());
    }

    private static StatementUpload upload(User user, String letra, String nome) {
        return StatementUpload.builder().user(user).fileHash(letra.repeat(64)).fileName(nome)
                .format("PLUGGY").transactionsImported(1).build();
    }

    private static BankTransaction linha(User user, String transactionId, String valor) {
        return BankTransaction.builder().user(user).transactionId(transactionId).type("DEBIT")
                .amount(new BigDecimal(valor)).description("IFOOD")
                .date(OffsetDateTime.of(2026, 5, 10, 12, 0, 0, 0, ZoneOffset.UTC))
                .reviewStatus(BankTransaction.ReviewStatus.SUGGESTED).build();
    }
}
