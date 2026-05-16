package br.com.painel_economico.service.statement.parser;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
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
}
