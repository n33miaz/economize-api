package br.com.economize.service.privacy;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Quais tabelas existem no banco conectado.
 *
 * <p>Existe por causa de uma diferença real entre produção e teste: o H2 da
 * suíte monta o schema pelo mapeamento JPA, e tabela sem entidade (como
 * {@code sweep_run_items}, só lida por SQL nativo) não existe lá. Exportar e
 * apagar pulam a tabela ausente em vez de quebrar; no Postgres todas existem.
 * Lido uma vez: o schema não muda com a aplicação no ar.
 */
@Component
public class SchemaTables {

    private final DataSource dataSource;
    private volatile Set<String> names;

    public SchemaTables(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean exists(String table) {
        return names().contains(table.toLowerCase(Locale.ROOT));
    }

    private Set<String> names() {
        Set<String> loaded = names;
        if (loaded != null) return loaded;
        Set<String> found = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet rs = connection.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) found.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        } catch (SQLException e) {
            throw new IllegalStateException("Não foi possível ler as tabelas do banco", e);
        }
        names = Set.copyOf(found);
        return names;
    }
}
