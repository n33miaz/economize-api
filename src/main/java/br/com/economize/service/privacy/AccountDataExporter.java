package br.com.economize.service.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Portabilidade da LGPD (art. 18, V): TODOS os dados da pessoa num ZIP, um CSV
 * por tipo de dado e um LEIA-ME que explica cada arquivo.
 *
 * <p><b>Por que {@code select *} e não uma lista de colunas.</b> O direito é a
 * TODOS os dados: uma lista fixa de colunas esqueceria em silêncio toda coluna
 * que uma migration futura acrescentar. O que não pode sair é decidido por
 * NOME de coluna ({@link #isWithheld}): os segredos conhecidos por nome exato e,
 * como rede para os futuros, qualquer coluna cujo nome fale em hash, cifra,
 * segredo ou senha. Errar aqui é deixar de exportar um campo; o contrário seria
 * entregar um segredo num arquivo que vai parar no e-mail e na nuvem da pessoa.
 *
 * <p><b>Streaming.</b> Cada linha vai do {@code ResultSet} direto para o ZIP; não
 * há lista de entidades nem CSV inteiro montado em memória. As consultas rodam
 * numa transação só leitura em REPEATABLE READ: o retrato sai coerente mesmo
 * que um sync do banco grave no meio do download.
 */
@Component
public class AccountDataExporter {

    /** Colunas que nunca saem, com o motivo no LEIA-ME. */
    static final Set<String> WITHHELD_COLUMNS = Set.of(
            "password",          // hash da senha
            "session_version",   // controle interno de sessões
            "api_key_cipher",    // chave de IA cifrada
            "api_key_last4",     // pedaço da chave de IA
            "master_key_id",     // qual chave do servidor cifrou a de IA
            "secret_cipher",     // segredo do autenticador (2FA)
            "last_used_step",    // contador interno do 2FA
            "code_hash",         // códigos de recuperação e convites
            "token_hash",        // redefinição de senha e aparelhos confiáveis
            "key_hash",          // chaves de integração
            "last_ip_hash");     // IP do aparelho, só como hash

    private static final Pattern SECRET_LIKE = Pattern.compile(".*(hash|cipher|secret|password|senha).*");

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final char SEPARATOR = ';';
    private static final int FETCH_SIZE = 500;

    /** Um arquivo do ZIP: a tabela, a consulta (sempre filtrada por :uid) e o que o LEIA-ME diz dele. */
    record Export(String file, String table, String description, String sql) {
    }

    // Subconsultas reaproveitadas: o que é "meu" quando a tabela não tem user_id
    private static final String MY_MEMBERSHIPS = "select id from family_members where user_id = :uid";

    static final List<Export> EXPORTS = List.of(
            new Export("perfil.csv", "users",
                    "Cadastro: nome, e-mail, datas de criação e último acesso, plano.",
                    "select * from users where id = :uid"),
            new Export("lancamentos.csv", "bank_transactions",
                    "Lançamentos do extrato (importados de arquivo, do banco conectado ou manuais), "
                            + "com categoria, revisão e marcas (transferência, estorno, ignorado).",
                    "select * from bank_transactions where user_id = :uid order by created_at, id"),
            new Export("extratos_importados.csv", "statement_uploads",
                    "Arquivos de extrato enviados: nome, formato e quantos lançamentos cada um trouxe.",
                    "select * from statement_uploads where user_id = :uid order by created_at, id"),
            new Export("contas.csv", "connector_accounts",
                    "Contas e cartões (origem dos lançamentos), com fechamento, vencimento, limite e saldo informado.",
                    "select * from connector_accounts where user_id = :uid order by created_at, id"),
            new Export("conexoes_bancarias.csv", "pluggy_items",
                    "Bancos conectados pelo Open Finance (Pluggy) e a situação de cada conexão.",
                    "select * from pluggy_items where user_id = :uid order by created_at, id"),
            new Export("faturas_cartao.csv", "card_bills",
                    "Faturas de cartão trazidas do banco conectado.",
                    "select * from card_bills where user_id = :uid order by closing_date, id"),
            new Export("reservas_fatura.csv", "invoice_reserves",
                    "Dinheiro separado para pagar fatura de cartão.",
                    "select * from invoice_reserves where user_id = :uid order by created_at, id"),
            new Export("categorias.csv", "categories",
                    "Categorias criadas por você (as padrão do app não entram).",
                    "select * from categories where user_id = :uid order by created_at, id"),
            new Export("regras_categoria.csv", "category_rules",
                    "Regras que categorizam lançamentos automaticamente.",
                    "select * from category_rules where user_id = :uid order by created_at, id"),
            new Export("orcamentos.csv", "category_budgets",
                    "Tetos mensais por categoria.",
                    "select * from category_budgets where user_id = :uid order by created_at, id"),
            new Export("recorrencias.csv", "recurring_series",
                    "Contas e receitas recorrentes (assinaturas, salário, aluguel...).",
                    "select * from recurring_series where user_id = :uid order by created_at, id"),
            new Export("recorrencias_lancamentos.csv", "recurring_series_links",
                    "Quais lançamentos pertencem a cada recorrência.",
                    "select * from recurring_series_links where series_id in "
                            + "(select id from recurring_series where user_id = :uid) order by matched_at, id"),
            new Export("fontes_renda.csv", "income_sources",
                    "Fontes de renda cadastradas ou confirmadas.",
                    "select * from income_sources where user_id = :uid order by created_at, id"),
            new Export("jornada_trabalho.csv", "work_profiles",
                    "Dias e horas de trabalho (usados para o custo em horas).",
                    "select * from work_profiles where user_id = :uid"),
            new Export("metas.csv", "wishes",
                    "Desejos e metas de compra.",
                    "select * from wishes where user_id = :uid order by created_at, id"),
            new Export("metas_aportes.csv", "wish_contributions",
                    "Aportes feitos em cada meta.",
                    "select * from wish_contributions where user_id = :uid order by created_at, id"),
            new Export("investimentos.csv", "investment_positions",
                    "Posições de investimento (do banco conectado ou cadastradas).",
                    "select * from investment_positions where user_id = :uid order by created_at, id"),
            new Export("investimentos_interesse.csv", "investment_interests",
                    "Ativos que você marcou para acompanhar.",
                    "select * from investment_interests where user_id = :uid order by created_at, id"),
            new Export("carteira_operacoes.csv", "transactions",
                    "Operações de compra e venda da carteira.",
                    "select * from transactions where user_id = :uid order by transaction_date, id"),
            new Export("relatorios.csv", "reports",
                    "Relatórios gerados para você.",
                    "select * from reports where user_id = :uid order by created_at, id"),
            new Export("limpezas_automaticas.csv", "sweep_runs",
                    "Arrumações automáticas do extrato (duplicatas, transferências) e se foram desfeitas.",
                    "select * from sweep_runs where user_id = :uid order by ran_at, id"),
            new Export("limpezas_lancamentos.csv", "sweep_run_items",
                    "Quais lançamentos cada arrumação automática tocou.",
                    "select * from sweep_run_items where run_id in "
                            + "(select id from sweep_runs where user_id = :uid)"),
            new Export("casa.csv", "family_groups",
                    "A casa (família) de que você participa.",
                    "select * from family_groups where id in "
                            + "(select group_id from family_members where user_id = :uid)"),
            new Export("casa_participacao.csv", "family_members",
                    "Sua participação na casa: papel e o que você compartilha.",
                    "select * from family_members where user_id = :uid"),
            new Export("casa_categorias_ocultas.csv", "family_member_hidden_categories",
                    "Categorias que você escondeu da casa.",
                    "select * from family_member_hidden_categories where member_id in (" + MY_MEMBERSHIPS + ")"),
            new Export("casa_contas_compartilhadas.csv", "family_member_shared_accounts",
                    "Contas que você mostra para a casa (vazio = todas).",
                    "select * from family_member_shared_accounts where member_id in (" + MY_MEMBERSHIPS + ")"),
            new Export("casa_convites.csv", "family_invites",
                    "Convites que você emitiu (sem o código).",
                    "select * from family_invites where created_by = :uid order by created_at, id"),
            new Export("compras.csv", "shopping_trips",
                    "Idas ao mercado: loja, orçamento, total da nota.",
                    "select * from shopping_trips where user_id = :uid order by created_at, id"),
            new Export("compras_itens.csv", "shopping_items",
                    "Itens das suas listas de compras e os que você pôs na lista da casa.",
                    "select * from shopping_items where trip_id in "
                            + "(select id from shopping_trips where user_id = :uid) or added_by = :uid "
                            + "order by created_at, id"),
            new Export("preferencias_compra.csv", "purchase_preferences",
                    "Como você costuma pagar as compras.",
                    "select * from purchase_preferences where user_id = :uid"),
            new Export("configuracao_ia.csv", "user_ai_settings",
                    "Provedor e modelo de IA escolhidos (sem a chave).",
                    "select * from user_ai_settings where user_id = :uid"),
            new Export("seguranca_2fa.csv", "user_mfa",
                    "Se o segundo fator está ligado e desde quando (sem o segredo).",
                    "select * from user_mfa where user_id = :uid"),
            new Export("aparelhos_confiaveis.csv", "trusted_devices",
                    "Aparelhos que dispensam o código do segundo fator.",
                    "select * from trusted_devices where user_id = :uid order by created_at, id"),
            new Export("chaves_integracao.csv", "integration_keys",
                    "Chaves de integração: nome, início visível, uso e revogação (sem a chave).",
                    "select * from integration_keys where user_id = :uid order by created_at, id"),
            new Export("planos_interesse.csv", "plan_interest",
                    "Planos pelos quais você demonstrou interesse.",
                    "select * from plan_interest where user_id = :uid order by created_at, id"),
            new Export("suporte.csv", "support_tickets",
                    "Chamados de suporte e as respostas.",
                    "select * from support_tickets where user_id = :uid order by created_at, id"));

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate readOnlySnapshot;
    private final SchemaTables tables;

    public AccountDataExporter(DataSource dataSource, PlatformTransactionManager transactionManager,
                               SchemaTables tables) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setFetchSize(FETCH_SIZE);
        this.jdbc = new NamedParameterJdbcTemplate(template);
        this.readOnlySnapshot = new TransactionTemplate(transactionManager);
        this.readOnlySnapshot.setReadOnly(true);
        this.readOnlySnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.tables = tables;
    }

    /** Coluna fora da exportação? Pelo nome exato ou pelo jeito de segredo. */
    static boolean isWithheld(String column) {
        String name = column.toLowerCase(Locale.ROOT);
        return WITHHELD_COLUMNS.contains(name) || SECRET_LIKE.matcher(name).matches();
    }

    /**
     * Escreve o ZIP inteiro em {@code out}. Não fecha {@code out} além do
     * fechamento do próprio ZIP (que fecha o fluxo de baixo, como o publicador
     * de streaming espera).
     */
    public void write(UUID userId, OutputStream out) {
        readOnlySnapshot.executeWithoutResult(status -> {
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (Export export : EXPORTS) {
                    // tabela que só existe no Postgres (sem entidade JPA) não existe no H2 de teste
                    if (!tables.exists(export.table())) continue;
                    counts.put(export.file(), writeCsv(zip, export, userId));
                }
                writeReadme(zip, counts);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private int writeCsv(ZipOutputStream zip, Export export, UUID userId) throws IOException {
        zip.putNextEntry(new ZipEntry(export.file()));
        zip.write(BOM);
        // sem close: fecharia o ZIP inteiro; o flush ao fim da entrada basta
        Writer writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
        // extrator e não callback por linha: o cabeçalho sai mesmo sem nenhuma
        // linha, e o arquivo vazio ainda diz quais campos existem
        Integer rows = jdbc.query(export.sql(), new MapSqlParameterSource("uid", userId), (ResultSet rs) -> {
            try {
                List<Integer> kept = writeHeader(writer, rs.getMetaData());
                int count = 0;
                while (rs.next()) {
                    writeRow(writer, rs, kept);
                    count++;
                }
                return count;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        writer.flush();
        zip.closeEntry();
        return rows == null ? 0 : rows;
    }

    /** Escreve o cabeçalho e devolve os índices das colunas que entram. */
    private static List<Integer> writeHeader(Writer writer, ResultSetMetaData meta)
            throws SQLException, IOException {
        List<Integer> kept = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String name = meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
            if (isWithheld(name)) continue;
            kept.add(i);
            names.add(name);
        }
        writer.write(String.join(String.valueOf(SEPARATOR), names));
        writer.write("\r\n");
        return kept;
    }

    private static void writeRow(Writer writer, ResultSet rs, List<Integer> kept) throws SQLException, IOException {
        for (int k = 0; k < kept.size(); k++) {
            if (k > 0) writer.write(SEPARATOR);
            writer.write(cell(rs.getObject(kept.get(k))));
        }
        writer.write("\r\n");
    }

    /**
     * Um valor em texto: datas em ISO-8601, números com ponto decimal e sem
     * notação científica, texto entre aspas quando precisa.
     */
    static String cell(Object value) throws SQLException {
        if (value == null) return "";
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof java.sql.Time time) return time.toLocalTime().toString();
        if (value instanceof Instant instant) return instant.toString();
        if (value instanceof Number || value instanceof Boolean || value instanceof UUID
                || value instanceof java.time.temporal.Temporal) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if (value instanceof Clob clob) return text(clob.getSubString(1, (int) clob.length()));
        if (value instanceof java.sql.Array array) {
            Object[] items = (Object[]) array.getArray();
            List<String> parts = new ArrayList<>(items.length);
            for (Object item : items) parts.add(item == null ? "" : item.toString());
            return text(String.join(",", parts));
        }
        return text(value.toString());
    }

    /**
     * Texto livre do CSV. Duas proteções: aspas quando o valor tem separador,
     * aspas ou quebra de linha; e um apóstrofo na frente de quem começa como
     * fórmula — descrição de lançamento vem do banco e item de compra pode vir
     * de outro membro da casa, e o Excel executaria "=HYPERLINK(...)" ao abrir.
     */
    static String text(String raw) {
        String value = raw;
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        boolean quote = value.indexOf(SEPARATOR) >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!quote) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static void writeReadme(ZipOutputStream zip, Map<String, Integer> counts) throws IOException {
        StringBuilder readme = new StringBuilder();
        readme.append("SEUS DADOS NO ECONOMIZE\r\n")
                .append("=======================\r\n\r\n")
                .append("Gerado em ").append(Instant.now()).append(" (UTC).\r\n\r\n")
                .append("Cada arquivo .csv é um tipo de dado. Formato: UTF-8, separador ponto e vírgula (;),\r\n")
                .append("datas em ISO-8601 (ex.: 2026-11-13T10:30:00Z, sempre em UTC quando têm hora),\r\n")
                .append("valores com ponto decimal (ex.: 1234.56; negativo = saída). Abre direto no Excel.\r\n")
                .append("Textos que começam com =, +, -, @ ganham um apóstrofo na frente, para o Excel\r\n")
                .append("não tratá-los como fórmula. As colunas *_id ligam os arquivos entre si.\r\n\r\n")
                .append("ARQUIVOS (linhas)\r\n-----------------\r\n");
        for (Export export : EXPORTS) {
            Integer rows = counts.get(export.file());
            if (rows == null) continue;
            readme.append(export.file()).append(" (").append(rows).append("): ")
                    .append(export.description()).append("\r\n");
        }
        readme.append("\r\nO QUE NÃO ESTÁ AQUI, E POR QUÊ\r\n------------------------------\r\n")
                .append("- Senha: guardamos só um resumo irreversível (hash); nem nós sabemos a senha.\r\n")
                .append("- Chave de IA: fica cifrada e serve só para chamar o provedor em seu nome; quem tem\r\n")
                .append("  a chave é você, no site do provedor.\r\n")
                .append("- Segredo do autenticador e códigos de recuperação do 2FA: são a sua segunda chave;\r\n")
                .append("  num arquivo que vai para e-mail ou nuvem, deixariam de proteger a conta.\r\n")
                .append("- Hashes de convites, de chaves de integração, de aparelhos confiáveis, de links de\r\n")
                .append("  redefinição de senha e do IP: são resumos irreversíveis, sem informação legível.\r\n")
                .append("- Dados de outros membros da casa: são deles, não seus.\r\n")
                .append("- Categorias padrão do app e cotações de mercado: não são dados pessoais.\r\n");
        zip.putNextEntry(new ZipEntry("LEIA-ME.txt"));
        zip.write(BOM);
        zip.write(readme.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
