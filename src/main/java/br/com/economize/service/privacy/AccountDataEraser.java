package br.com.economize.service.privacy;

import br.com.economize.exception.ResourceConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Apaga do banco tudo o que é da pessoa — a exclusão de conta da LGPD (art. 18, VI).
 *
 * <p><b>Por que um DELETE por tabela, se o Postgres tem ON DELETE CASCADE.</b>
 * Em produção, apagar a linha de {@code users} já levaria quase tudo (as FKs
 * para users são CASCADE desde a V1). Mas o H2 da suíte monta o schema pelo
 * mapeamento JPA, sem a regra do FK, e as tabelas com o dono só em UUID solto
 * (chaves de integração, chamados, aportes, compras) nem FK têm lá. Um efeito
 * que só existe em produção é um efeito que ninguém testa — o mesmo motivo do
 * {@code FamilyService.delete}. A ordem abaixo é a das dependências, filhos
 * antes dos pais, e vale nos dois bancos.
 *
 * <p>Só SQL e só banco: senha, segundo fator, sessões e o Pluggy ficam no
 * {@link AccountPrivacyService}. Assim a suíte Postgres exercita este passo
 * sozinho, contra as FKs reais.
 */
@Slf4j
@Component
public class AccountDataEraser {

    static final String OWNER_WITH_MEMBERS_MESSAGE =
            "Você criou uma casa que tem outros membros. Remova os membros ou desfaça a casa "
                    + "antes de excluir a conta.";

    private static final String OWNED_GROUPS = "select id from family_groups where owner_user_id = :uid";
    // membros da casa que é minha (só eu, quando a exclusão chega aqui) e a minha participação na casa alheia
    private static final String AFFECTED_MEMBERS = "select id from family_members where group_id in ("
            + OWNED_GROUPS + ") or user_id = :uid";

    /** Tabela + comando, na ordem em que precisam rodar. */
    private record Step(String table, String sql) {
    }

    private static final List<Step> STEPS = List.of(
            // casa: as coleções do membro antes do membro, o membro antes da casa
            new Step("family_member_hidden_categories",
                    "delete from family_member_hidden_categories where member_id in (" + AFFECTED_MEMBERS + ")"),
            new Step("family_member_shared_accounts",
                    "delete from family_member_shared_accounts where member_id in (" + AFFECTED_MEMBERS + ")"),
            new Step("family_invites",
                    "delete from family_invites where group_id in (" + OWNED_GROUPS + ") or created_by = :uid"),
            new Step("shopping_trips",
                    "update shopping_trips set family_group_id = null where family_group_id in (" + OWNED_GROUPS + ")"),
            new Step("family_members", "delete from family_members where id in (" + AFFECTED_MEMBERS + ")"),
            new Step("family_groups", "delete from family_groups where owner_user_id = :uid"),

            // compras: o que eu pus na lista de outra pessoa fica para ela, sem autor (SET NULL da V39)
            new Step("shopping_items",
                    "delete from shopping_items where trip_id in (select id from shopping_trips where user_id = :uid)"),
            new Step("shopping_items", "update shopping_items set added_by = null where added_by = :uid"),
            new Step("shopping_trips", "delete from shopping_trips where user_id = :uid"),
            new Step("purchase_preferences", "delete from purchase_preferences where user_id = :uid"),

            // o que aponta para lançamentos, recorrências e metas
            new Step("sweep_run_items",
                    "delete from sweep_run_items where run_id in (select id from sweep_runs where user_id = :uid)"),
            new Step("sweep_runs", "delete from sweep_runs where user_id = :uid"),
            new Step("recurring_series_links",
                    "delete from recurring_series_links where series_id in "
                            + "(select id from recurring_series where user_id = :uid)"),
            new Step("income_sources", "delete from income_sources where user_id = :uid"),
            new Step("work_profiles", "delete from work_profiles where user_id = :uid"),
            new Step("wish_contributions", "delete from wish_contributions where user_id = :uid "
                    + "or wish_id in (select id from wishes where user_id = :uid)"),
            new Step("wishes", "delete from wishes where user_id = :uid"),
            new Step("category_budgets", "delete from category_budgets where user_id = :uid"),
            new Step("invoice_reserves", "delete from invoice_reserves where user_id = :uid"),
            new Step("card_bills", "delete from card_bills where user_id = :uid"),
            new Step("investment_positions", "delete from investment_positions where user_id = :uid"),
            new Step("investment_interests", "delete from investment_interests where user_id = :uid"),
            new Step("recurring_series", "delete from recurring_series where user_id = :uid"),

            // extrato e carteira
            new Step("bank_transactions", "delete from bank_transactions where user_id = :uid"),
            new Step("statement_uploads", "delete from statement_uploads where user_id = :uid"),
            new Step("transactions", "delete from transactions where user_id = :uid"),
            new Step("reports", "delete from reports where user_id = :uid"),

            // contas: limite compartilhado de outra pessoa com um cartão meu solta o vínculo (SET NULL da V36)
            new Step("connector_accounts", "update connector_accounts set credit_limit_shared_with = null "
                    + "where credit_limit_shared_with in (select id from connector_accounts where user_id = :uid)"),
            new Step("connector_accounts", "delete from connector_accounts where user_id = :uid"),
            new Step("pluggy_items", "delete from pluggy_items where user_id = :uid"),

            // categorias: soltar a hierarquia antes, porque parent_id aponta para a própria tabela
            new Step("category_rules", "delete from category_rules where user_id = :uid"),
            new Step("categories", "update categories set parent_id = null where user_id = :uid"),
            new Step("categories", "delete from categories where user_id = :uid"),

            // acesso e configurações
            new Step("user_ai_settings", "delete from user_ai_settings where user_id = :uid"),
            new Step("mfa_recovery_codes", "delete from mfa_recovery_codes where user_id = :uid"),
            new Step("user_mfa", "delete from user_mfa where user_id = :uid"),
            new Step("trusted_devices", "delete from trusted_devices where user_id = :uid"),
            new Step("password_reset_tokens", "delete from password_reset_tokens where user_id = :uid"),
            new Step("integration_keys", "delete from integration_keys where user_id = :uid"),
            new Step("plan_interest", "delete from plan_interest where user_id = :uid"),
            new Step("support_tickets", "delete from support_tickets where user_id = :uid"),

            new Step("users", "delete from users where id = :uid"));

    private final NamedParameterJdbcTemplate jdbc;
    private final SchemaTables tables;

    public AccountDataEraser(NamedParameterJdbcTemplate jdbc, SchemaTables tables) {
        this.jdbc = jdbc;
        this.tables = tables;
    }

    /**
     * Recusa se a pessoa criou uma casa que ainda tem outros membros: apagar a
     * conta desfaria a casa de todos sem ninguém ter pedido. Não existe
     * transferência de dono, então a saída é remover os membros ou desfazer.
     */
    public void requireNoHouseholdToOrphan(UUID userId) {
        Integer others = jdbc.queryForObject(
                "select count(*) from family_members m where m.group_id in (" + OWNED_GROUPS + ") "
                        + "and m.user_id <> :uid",
                params(userId), Integer.class);
        if (others != null && others > 0) {
            throw new ResourceConflictException(OWNER_WITH_MEMBERS_MESSAGE);
        }
    }

    /** Apaga tudo, na transação de quem chama (sem ela, abre uma: nunca apaga pela metade). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void eraseAll(UUID userId) {
        requireNoHouseholdToOrphan(userId);
        int total = 0;
        for (Step step : STEPS) {
            if (!tables.exists(step.table())) continue;
            total += jdbc.update(step.sql(), params(userId));
        }
        log.info("Conta {} excluída a pedido do titular ({} linhas)", userId, total);
    }

    private static MapSqlParameterSource params(UUID userId) {
        return new MapSqlParameterSource("uid", userId);
    }
}
