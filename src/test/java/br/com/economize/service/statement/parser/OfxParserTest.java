package br.com.economize.service.statement.parser;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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

    /**
     * O saldo do arquivo (EC: "3.021,06 não existe", 15/09/2026).
     *
     * <p>O dado sempre esteve no OFX e era descartado; o app somava movimento e
     * chamava de saldo. Os testes abaixo guardam as três decisões que importam:
     * lê o LEDGERBAL, NÃO lê o AVAILBAL (que soma limite de cheque especial), e
     * devolve nulo — nunca zero — quando o arquivo não informa nada.
     */
    @Test
    void leOSaldoDeclaradoNoLedgerbal() {
        String ofx = """
                <OFX>
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260101120000
                <TRNAMT>-50.25
                <FITID>TX-001
                <MEMO>IFOOD
                </STMTTRN>
                <LEDGERBAL><BALAMT>4213.83<DTASOF>20260915120000</LEDGERBAL>
                </OFX>
                """;

        StatementBalance saldo = parser.parseBalance(new ByteArrayInputStream(ofx.getBytes(StandardCharsets.UTF_8)));

        assertThat(saldo).isNotNull();
        assertThat(saldo.amount()).isEqualByComparingTo("4213.83");
        assertThat(saldo.asOf()).isEqualTo(OffsetDateTime.parse("2026-09-15T12:00:00Z"));
    }

    /**
     * Limite de cheque especial não é dinheiro do usuário. O AVAILBAL vem logo
     * depois do LEDGERBAL na maioria dos bancos e costuma ser MAIOR: capturá-lo
     * por engano inventaria saldo — o erro exato que este trabalho veio
     * consertar, só que ao contrário.
     */
    @Test
    void ignoraOSaldoDisponivelQueSomaLimite() {
        String ofx = """
                <OFX>
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260101120000
                <TRNAMT>-10.00
                <FITID>TX-009
                <MEMO>PADARIA
                </STMTTRN>
                <LEDGERBAL><BALAMT>120.00<DTASOF>20260910120000</LEDGERBAL>
                <AVAILBAL><BALAMT>5120.00<DTASOF>20260910120000</AVAILBAL>
                </OFX>
                """;

        StatementBalance saldo = parser.parseBalance(new ByteArrayInputStream(ofx.getBytes(StandardCharsets.UTF_8)));

        assertThat(saldo).isNotNull();
        assertThat(saldo.amount()).isEqualByComparingTo("120.00");
    }

    /**
     * SGML de banco raramente fecha tag. Sem tolerância no fecho, o bloco
     * engoliria o resto do arquivo e o BALAMT capturado seria o do AVAILBAL.
     */
    @Test
    void leOSaldoMesmoComTagSemFechamento() {
        String ofx = """
                <OFX>
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260101120000
                <TRNAMT>-10.00
                <FITID>TX-010
                <MEMO>PADARIA
                </STMTTRN>
                <LEDGERBAL>
                <BALAMT>77.50
                <DTASOF>20260914000000
                <AVAILBAL>
                <BALAMT>9999.00
                <DTASOF>20260914000000
                </STMTRS>
                </OFX>
                """;

        StatementBalance saldo = parser.parseBalance(new ByteArrayInputStream(ofx.getBytes(StandardCharsets.UTF_8)));

        assertThat(saldo).isNotNull();
        assertThat(saldo.amount()).isEqualByComparingTo("77.50");
    }

    /**
     * Nulo e não zero. Zero é um saldo — e um saldo falso vira número na tela
     * do usuário; nulo é "este arquivo não informa", que é a verdade e o que as
     * telas sabem tratar.
     */
    @Test
    void semBlocoDeSaldoDevolveNuloEmVezDeZero() {
        String ofx = """
                <OFX>
                <STMTTRN>
                <TRNTYPE>CREDIT
                <DTPOSTED>20260101120000
                <TRNAMT>10.00
                <FITID>TX-011
                <MEMO>PIX
                </STMTTRN>
                </OFX>
                """;

        assertThat(parser.parseBalance(new ByteArrayInputStream(ofx.getBytes(StandardCharsets.UTF_8)))).isNull();
    }

    /** Fatura de cartão declara o DEVIDO no mesmo bloco, e ele vem negativo. */
    @Test
    void leODevidoDaFaturaDeCartao() {
        String ofx = """
                <OFX>
                <CCSTMTRS>
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260101120000
                <TRNAMT>-89.90
                <FITID>TX-012
                <MEMO>MERCADO
                </STMTTRN>
                <LEDGERBAL><BALAMT>-1432.17<DTASOF>20260912000000</LEDGERBAL>
                </CCSTMTRS>
                </OFX>
                """;

        StatementBalance saldo = parser.parseBalance(new ByteArrayInputStream(ofx.getBytes(StandardCharsets.UTF_8)));

        assertThat(saldo).isNotNull();
        assertThat(saldo.amount()).isEqualByComparingTo("-1432.17");
    }
}
