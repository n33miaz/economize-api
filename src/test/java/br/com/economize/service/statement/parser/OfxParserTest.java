package br.com.economize.service.statement.parser;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OfxParserTest {

    private final OfxParser parser = new OfxParser();

    @Test
    void parsesValidOfxBlocks() {
        String ofx = """
                <OFX>
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260101120000[-3:BRT]
                <TRNAMT>-50.25
                <FITID>TX-001
                <MEMO>IFOOD ORDER 123
                </STMTTRN>
                <STMTTRN>
                <TRNTYPE>CREDIT
                <DTPOSTED>20260102120000[-3:BRT]
                <TRNAMT>1500.00
                <FITID>TX-002
                <MEMO>SALARIO
                </STMTTRN>
                </OFX>
                """;

        List<ParsedTransaction> result = parser.parse(new ByteArrayInputStream(ofx.getBytes(StandardCharsets.UTF_8)));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getExternalId()).isEqualTo("TX-001");
        assertThat(result.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("-50.25"));
        assertThat(result.get(0).getDescription()).contains("IFOOD");
        assertThat(result.get(1).getType()).isEqualTo("CREDIT");
    }

    @Test
    void returnsEmptyWhenNoStmttrn() {
        String ofx = "<OFX></OFX>";
        List<ParsedTransaction> result = parser.parse(new ByteArrayInputStream(ofx.getBytes(StandardCharsets.UTF_8)));
        assertThat(result).isEmpty();
    }

    // Cabeçalho sintético no formato do banco que mente: declara 1252 e grava
    // UTF-8. O conteúdo é fictício; só a ESTRUTURA do defeito é reproduzida.
    private static final String HEADER_1252 = """
            OFXHEADER:100
            DATA:OFXSGML
            VERSION:102
            ENCODING:USASCII
            CHARSET:1252
            COMPRESSION:NONE

            """;

    private static String stmtrn(String memo, String name) {
        return "<OFX><STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20260301120000<TRNAMT>12.50<FITID>TX-9"
                + "<MEMO>" + memo + "</MEMO><NAME>" + name + "</NAME></STMTTRN></OFX>";
    }

    @Test
    void prefersUtf8BytesOverLyingCharsetHeader() {
        // bytes C3 A7 C3 A3 ("çã") sob CHARSET:1252 — honrar o cabeçalho daria "AplicaÃ§Ã£o"
        byte[] bytes = (HEADER_1252 + stmtrn("Estorno: \"CDB Cofre Objetivo\"", "Aplicação"))
                .getBytes(StandardCharsets.UTF_8);

        List<ParsedTransaction> result = parser.parse(new ByteArrayInputStream(bytes));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDescription()).isEqualTo("Estorno: \"CDB Cofre Objetivo\" Aplicação");
        assertThat(result.get(0).getDescription()).doesNotContain("Ã");
    }

    @Test
    void honorsDeclaredCharsetWhenBytesAreGenuinelyWindows1252() {
        // "ç" = E7 e "ã" = E3 em 1252: bytes soltos inválidos em UTF-8 estrito,
        // então a declaração continua mandando e o texto sai íntegro
        byte[] bytes = (HEADER_1252 + stmtrn("Compra: \"Padaria Estação\"", "Cartão"))
                .getBytes(Charset.forName("windows-1252"));

        List<ParsedTransaction> result = parser.parse(new ByteArrayInputStream(bytes));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDescription()).isEqualTo("Compra: \"Padaria Estação\" Cartão");
    }

    @Test
    void pureAsciiUnderAnyHeaderIsUnchanged() {
        byte[] bytes = (HEADER_1252 + stmtrn("Pix recebido: \"Cp :123-Maria Souza\"", "Maria Souza"))
                .getBytes(StandardCharsets.US_ASCII);

        List<ParsedTransaction> result = parser.parse(new ByteArrayInputStream(bytes));

        assertThat(result.get(0).getDescription()).isEqualTo("Pix recebido: \"Cp :123-Maria Souza\"");
    }
}
