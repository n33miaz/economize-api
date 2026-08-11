package br.com.economize.service.statement.parser;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvParserTest {

    private final CsvParser parser = new CsvParser();

    @Test
    void parsesNubankLikeCsv() {
        String csv = """
                Data,Descrição,Valor
                01/05/2026,IFOOD ORDER,-45.90
                02/05/2026,Salário,3500.00
                """;

        List<ParsedTransaction> result = parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("-45.90"));
        assertThat(result.get(0).getType()).isEqualTo("DEBIT");
        assertThat(result.get(1).getType()).isEqualTo("CREDIT");
    }

    @Test
    void supportsSemicolonDelimiter() {
        String csv = """
                Data;Descricao;Valor
                10/04/2026;Aluguel;-1.500,00
                """;

        List<ParsedTransaction> result = parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("-1500.00"));
    }
}
