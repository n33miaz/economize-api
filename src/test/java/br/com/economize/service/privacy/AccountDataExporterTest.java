package br.com.economize.service.privacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Formato do CSV da exportação")
class AccountDataExporterTest {

    @Test
    @DisplayName("número com ponto e sem notação científica; data em ISO; nulo vazio")
    void valuesAreFormattedForExcel() throws SQLException {
        assertThat(AccountDataExporter.cell(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(AccountDataExporter.cell(new BigDecimal("-12.50"))).isEqualTo("-12.50");
        assertThat(AccountDataExporter.cell(Timestamp.from(Instant.parse("2026-11-13T10:30:00Z"))))
                .isEqualTo("2026-11-13T10:30:00Z");
        assertThat(AccountDataExporter.cell(null)).isEmpty();
    }

    @Test
    @DisplayName("texto com separador, aspas ou quebra vai entre aspas; fórmula ganha apóstrofo")
    void textIsQuotedAndDefused() {
        assertThat(AccountDataExporter.text("simples")).isEqualTo("simples");
        assertThat(AccountDataExporter.text("a;b")).isEqualTo("\"a;b\"");
        assertThat(AccountDataExporter.text("diz \"oi\"")).isEqualTo("\"diz \"\"oi\"\"\"");
        assertThat(AccountDataExporter.text("linha\nnova")).isEqualTo("\"linha\nnova\"");
        assertThat(AccountDataExporter.text("=HYPERLINK(\"x\")")).startsWith("\"'=HYPERLINK");
        assertThat(AccountDataExporter.text("@soma")).isEqualTo("'@soma");
    }

    @Test
    @DisplayName("segredos ficam de fora pelo nome, inclusive colunas futuras com cara de segredo")
    void secretColumnsAreWithheld() {
        assertThat(AccountDataExporter.isWithheld("password")).isTrue();
        assertThat(AccountDataExporter.isWithheld("API_KEY_CIPHER")).isTrue();
        assertThat(AccountDataExporter.isWithheld("secret_cipher")).isTrue();
        assertThat(AccountDataExporter.isWithheld("token_hash")).isTrue();
        assertThat(AccountDataExporter.isWithheld("refresh_secret")).isTrue();
        assertThat(AccountDataExporter.isWithheld("description")).isFalse();
        assertThat(AccountDataExporter.isWithheld("prefix")).isFalse();
    }
}
