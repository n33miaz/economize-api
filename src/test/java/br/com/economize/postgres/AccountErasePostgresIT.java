package br.com.economize.postgres;

import br.com.economize.exception.ResourceConflictException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.service.privacy.AccountDataEraser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exclusão de conta (LGPD) contra as FKs de verdade.
 *
 * <p>O H2 da suíte não tem as FKs da produção (nem as CASCADE, nem as
 * auto-referências de categorias e lançamentos). Aqui as migrations rodam de
 * verdade: cada DELETE do {@link AccountDataEraser} enfrenta as mesmas
 * restrições do Postgres de produção, e a conferência final varre TODA coluna
 * que aponta para {@code users(id)} — tabela esquecida falha aqui.
 */
@DisplayName("Postgres: exclusão de conta apaga tudo da pessoa e nada dos outros")
class AccountErasePostgresIT extends PostgresIntegrationBase {

    @Autowired
    private AccountDataEraser eraser;

    @Autowired
    private BankTransactionRepository bankTransactionRepository;

    @Test
    @DisplayName("apaga em todas as tabelas, com as dependências cruzadas, e preserva o outro usuário")
    void erasesEverythingAndKeepsOthers() {
        User me = novoUsuario();
        User other = novoUsuario();
        UUID myTx = lancamento(me, "MEU");
        UUID otherTx = lancamento(other, "DO OUTRO");

        tx.executeWithoutResult(s -> {
            UUID me_ = me.getId();
            // categoria com filha (auto-referência) e regra
            UUID parent = uuid();
            UUID child = uuid();
            jdbc.update("insert into categories (id, user_id, name, slug) values (?, ?, 'Casa', ?)",
                    parent, me_, "casa-" + parent);
            jdbc.update("insert into categories (id, user_id, name, slug, parent_id) values (?, ?, 'Luz', ?, ?)",
                    child, me_, "luz-" + child, parent);
            jdbc.update("insert into category_rules (id, user_id, category_id, pattern, origin) values (?, ?, ?, 'ENEL', 'USER')",
                    uuid(), me_, child);
            // cartão, reserva de fatura e limite compartilhado por OUTRA pessoa com ele
            UUID card = conta(me_, "CREDIT_CARD");
            UUID otherCard = conta(other.getId(), "CREDIT_CARD");
            jdbc.update("update connector_accounts set credit_limit_shared_with = ? where id = ?", card, otherCard);
            jdbc.update("insert into invoice_reserves (id, user_id, card_account_id, reference, amount) "
                    + "values (?, ?, ?, '2026-11', 10)", uuid(), me_, card);
            // recorrência ligada a um lançamento
            UUID series = uuid();
            jdbc.update("insert into recurring_series (id, user_id, merchant_key, flow, cadence, amount_type, source) "
                    + "values (?, ?, 'NETFLIX', 'EXPENSE', 'MONTHLY', 'FIXED', 'USER')", series, me_);
            jdbc.update("insert into recurring_series_links (id, series_id, bank_transaction_id) values (?, ?, ?)",
                    uuid(), series, myTx);
            // meta com aporte
            UUID wish = uuid();
            jdbc.update("insert into wishes (id, user_id, name, target_amount) values (?, ?, 'Bicicleta', 100)",
                    wish, me_);
            jdbc.update("insert into wish_contributions (id, wish_id, user_id, amount) values (?, ?, ?, 5)",
                    uuid(), wish, me_);
            // arrumação automática que tocou um lançamento
            UUID run = uuid();
            jdbc.update("insert into sweep_runs (id, user_id, kind) values (?, ?, 'INTERNAL_TRANSFER')", run, me_);
            jdbc.update("insert into sweep_run_items (run_id, transaction_id) values (?, ?)", run, myTx);
            // casa só minha, com categoria escondida
            UUID group = uuid();
            UUID member = uuid();
            jdbc.update("insert into family_groups (id, name, owner_user_id) values (?, 'Só eu', ?)", group, me_);
            jdbc.update("insert into family_members (id, group_id, user_id, role, share_scope) "
                    + "values (?, ?, ?, 'OWNER', 'TOTALS')", member, group, me_);
            jdbc.update("insert into family_member_hidden_categories (member_id, category_id) values (?, ?)",
                    member, child);
            // minha lista de compras, e um item que eu pus na lista de outra pessoa
            UUID myTrip = viagem(me_);
            item(myTrip, me_);
            UUID otherTrip = viagem(other.getId());
            UUID foreignItem = item(otherTrip, me_);
            // o outro usuário mantém o item, sem autor (SET NULL)
            assertThat(foreignItem).isNotNull();
        });

        tx.executeWithoutResult(s -> eraser.eraseAll(me.getId()));

        assertThat(rowsReferencing(me.getId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, me.getId()))
                .isZero();
        // o outro segue inteiro: lançamento, cartão (sem o vínculo) e a lista com o item que eu pus
        assertThat(bankTransactionRepository.findById(otherTx)).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from connector_accounts where user_id = ? "
                + "and credit_limit_shared_with is null", Integer.class, other.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from shopping_items i join shopping_trips t "
                + "on t.id = i.trip_id where t.user_id = ? and i.added_by is null", Integer.class, other.getId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("dono de casa com outros membros: recusa antes de apagar qualquer coisa")
    void ownerWithMembersIsRefused() {
        User owner = novoUsuario();
        User member = novoUsuario();
        lancamento(owner, "FICA");
        tx.executeWithoutResult(s -> {
            UUID group = uuid();
            jdbc.update("insert into family_groups (id, name, owner_user_id) values (?, 'Casa', ?)",
                    group, owner.getId());
            jdbc.update("insert into family_members (id, group_id, user_id, role, share_scope) "
                    + "values (?, ?, ?, 'OWNER', 'TOTALS')", uuid(), group, owner.getId());
            jdbc.update("insert into family_members (id, group_id, user_id, role, share_scope) "
                    + "values (?, ?, ?, 'MEMBER', 'TOTALS')", uuid(), group, member.getId());
        });

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> eraser.eraseAll(owner.getId())))
                .isInstanceOf(ResourceConflictException.class);

        assertThat(bankTransactionRepository.countByUserId(owner.getId())).isEqualTo(1);
    }

    // --- apoio ---

    /** Toda coluna com FK para users(id), em todo o schema — e quantas linhas ainda apontam para a pessoa. */
    private Map<String, Integer> rowsReferencing(UUID userId) {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                select kcu.table_name, kcu.column_name
                  from information_schema.table_constraints tc
                  join information_schema.key_column_usage kcu
                    on kcu.constraint_name = tc.constraint_name and kcu.table_schema = tc.table_schema
                  join information_schema.constraint_column_usage ccu
                    on ccu.constraint_name = tc.constraint_name and ccu.table_schema = tc.table_schema
                 where tc.constraint_type = 'FOREIGN KEY' and ccu.table_name = 'users'
                """);
        assertThat(columns).hasSizeGreaterThan(25);
        Map<String, Integer> found = new HashMap<>();
        for (Map<String, Object> column : columns) {
            String table = column.get("table_name").toString();
            String name = column.get("column_name").toString();
            Integer count = jdbc.queryForObject(
                    "select count(*) from " + table + " where " + name + " = ?", Integer.class, userId);
            if (count != null && count > 0) found.put(table + "." + name, count);
        }
        return found;
    }

    private UUID lancamento(User user, String description) {
        return tx.execute(s -> bankTransactionRepository.save(BankTransaction.builder()
                .user(user)
                .transactionId(UUID.randomUUID().toString())
                .type("DEBIT")
                .amount(new BigDecimal("-10"))
                .description(description)
                .date(OffsetDateTime.now())
                .build()).getId());
    }

    private UUID conta(UUID userId, String type) {
        UUID id = uuid();
        jdbc.update("insert into connector_accounts (id, user_id, provider_account_id, name, type) "
                + "values (?, ?, ?, 'Cartão', ?)", id, userId, "acc-" + id, type);
        return id;
    }

    private UUID viagem(UUID userId) {
        UUID id = uuid();
        jdbc.update("insert into shopping_trips (id, user_id, client_id) values (?, ?, ?)", id, userId, "c-" + id);
        return id;
    }

    private UUID item(UUID tripId, UUID addedBy) {
        UUID id = uuid();
        jdbc.update("insert into shopping_items (id, trip_id, client_id, name, normalized_name, client_updated_at, "
                + "added_by) values (?, ?, ?, 'Leite', 'leite', now(), ?)", id, tripId, "i-" + id, addedBy);
        return id;
    }

    private static UUID uuid() {
        return UUID.randomUUID();
    }
}
