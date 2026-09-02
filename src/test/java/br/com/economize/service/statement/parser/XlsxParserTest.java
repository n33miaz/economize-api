package br.com.economize.service.statement.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O parser de planilha (EC-155): a porta de entrada de todo extrato exportado
 * em XLSX.
 *
 * <p>Cada banco exporta a mesma coisa de um jeito: a data ora é célula de data,
 * ora é texto em três formatos diferentes; o valor ora é número, ora texto com
 * vírgula decimal. Um erro aqui não quebra nada visivelmente — ele importa a
 * transação no dia errado, ou não a importa.
 */
@DisplayName("XlsxParser — a planilha como os bancos exportam")
class XlsxParserTest {

    private final XlsxParser parser = new XlsxParser();

    /** Monta uma planilha de verdade em memória: nada de dublê de POI. */
    private InputStream planilha(String[] cabecalho, Object[][] linhas) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Extrato");
            Row header = sheet.createRow(0);
            for (int i = 0; i < cabecalho.length; i++) {
                header.createCell(i).setCellValue(cabecalho[i]);
            }
            CellStyle estiloData = wb.createCellStyle();
            estiloData.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));

            for (int r = 0; r < linhas.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < linhas[r].length; c++) {
                    Object valor = linhas[r][c];
                    if (valor == null) continue;
                    Cell cell = row.createCell(c);
                    if (valor instanceof LocalDate data) {
                        cell.setCellValue(java.sql.Date.valueOf(data));
                        cell.setCellStyle(estiloData);
                    } else if (valor instanceof Number numero) {
                        cell.setCellValue(numero.doubleValue());
                    } else {
                        cell.setCellValue(valor.toString());
                    }
                }
            }
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    @Test
    @DisplayName("Declara o formato que atende")
    void declaraOFormato() {
        assertThat(parser.format()).isEqualTo(StatementFormat.XLSX);
    }

    @Test
    @DisplayName("Lê data como célula de data, com o dia intacto")
    void leCelulaDeData() throws IOException {
        List<ParsedTransaction> lidas = parser.parse(planilha(
                new String[] { "Data", "Descrição", "Valor" },
                new Object[][] { { LocalDate.of(2026, 3, 1), "MERCADO", -150.75 } }));

        assertThat(lidas).hasSize(1);
        // O serial do Excel interpretado no fuso da JVM jogaria o dia 1º para o
        // último dia do mês anterior num container fora de UTC
        assertThat(lidas.get(0).getDate().toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(lidas.get(0).getDate().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(lidas.get(0).getAmount()).isEqualByComparingTo("-150.75");
        assertThat(lidas.get(0).getType()).isEqualTo("DEBIT");
        assertThat(lidas.get(0).getDescription()).isEqualTo("MERCADO");
    }

    @Test
    @DisplayName("Lê data em texto nos três formatos que os bancos usam")
    void leDataEmTexto() throws IOException {
        List<ParsedTransaction> lidas = parser.parse(planilha(
                new String[] { "Data", "Descrição", "Valor" },
                new Object[][] {
                        { "05/03/2026", "A", 10 },
                        { "2026-03-06", "B", 20 },
                        { "07-03-2026", "C", 30 },
                }));

        assertThat(lidas).extracting(tx -> tx.getDate().toLocalDate())
                .containsExactly(
                        LocalDate.of(2026, 3, 5),
                        LocalDate.of(2026, 3, 6),
                        LocalDate.of(2026, 3, 7));
    }

    @Test
    @DisplayName("Valor em texto com vírgula decimal vira número")
    void leValorEmTextoComVirgula() throws IOException {
        List<ParsedTransaction> lidas = parser.parse(planilha(
                new String[] { "Data", "Descrição", "Valor" },
                new Object[][] { { "05/03/2026", "SALARIO", "4400,00" } }));

        assertThat(lidas.get(0).getAmount()).isEqualByComparingTo("4400.00");
        assertThat(lidas.get(0).getType()).isEqualTo("CREDIT");
    }

    @Test
    @DisplayName("O sinal decide entrada e saída, como no resto do sistema")
    void sinalDecideOTipo() throws IOException {
        List<ParsedTransaction> lidas = parser.parse(planilha(
                new String[] { "Data", "Valor" },
                new Object[][] { { "05/03/2026", 100 }, { "06/03/2026", -100 }, { "07/03/2026", 0 } }));

        assertThat(lidas).extracting(ParsedTransaction::getType)
                .containsExactly("CREDIT", "DEBIT", "CREDIT");
    }

    @Test
    @DisplayName("Aceita cabeçalho em inglês e os sinônimos de descrição")
    void aceitaSinonimosDeCabecalho() throws IOException {
        assertThat(parser.parse(planilha(
                new String[] { "date", "description", "amount" },
                new Object[][] { { "05/03/2026", "MARKET", 10 } })))
                .singleElement()
                .extracting(ParsedTransaction::getDescription).isEqualTo("MARKET");

        assertThat(parser.parse(planilha(
                new String[] { "Data", "Histórico", "Valor" },
                new Object[][] { { "05/03/2026", "COMPRA", 10 } })))
                .singleElement()
                .extracting(ParsedTransaction::getDescription).isEqualTo("COMPRA");
    }

    @Test
    @DisplayName("Cabeçalho com espaço e caixa diferente ainda casa")
    void cabecalhoComEspacoECaixa() throws IOException {
        List<ParsedTransaction> lidas = parser.parse(planilha(
                new String[] { " DATA ", " Valor " },
                new Object[][] { { "05/03/2026", 10 } }));

        assertThat(lidas).hasSize(1);
    }

    @Test
    @DisplayName("Sem coluna de data ou de valor, recusa com mensagem que ensina")
    void recusaSemColunasObrigatorias() throws IOException {
        InputStream semValor = planilha(
                new String[] { "Data", "Descrição" },
                new Object[][] { { "05/03/2026", "X" } });

        assertThatThrownBy(() -> parser.parse(semValor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Data e Valor");
    }

    @Test
    @DisplayName("Linha sem data ou sem valor é pulada, não derruba o arquivo")
    void linhaIncompletaEPulada() throws IOException {
        List<ParsedTransaction> lidas = parser.parse(planilha(
                new String[] { "Data", "Descrição", "Valor" },
                new Object[][] {
                        { "05/03/2026", "BOA", 10 },
                        { null, "SEM DATA", 20 },
                        { "07/03/2026", "SEM VALOR", null },
                        { "texto que não é data", "DATA RUIM", 30 },
                        { "08/03/2026", "VALOR RUIM", "abc" },
                }));

        // Uma linha estragada no meio do extrato não pode custar o extrato todo
        assertThat(lidas).extracting(ParsedTransaction::getDescription)
                .containsExactly("BOA");
    }

    @Test
    @DisplayName("Descrição ausente vira vazio, nunca nulo")
    void descricaoAusenteViraVazio() throws IOException {
        List<ParsedTransaction> lidas = parser.parse(planilha(
                new String[] { "Data", "Valor" },
                new Object[][] { { "05/03/2026", 10 } }));

        assertThat(lidas.get(0).getDescription()).isEmpty();
    }

    @Test
    @DisplayName("Cada linha ganha id externo próprio: reimportar não duplica")
    void idExternoEEstavel() throws IOException {
        Object[][] linhas = { { "05/03/2026", "A", 10 }, { "05/03/2026", "B", 10 } };

        List<ParsedTransaction> primeira = parser.parse(
                planilha(new String[] { "Data", "Descrição", "Valor" }, linhas));
        List<ParsedTransaction> segunda = parser.parse(
                planilha(new String[] { "Data", "Descrição", "Valor" }, linhas));

        // Mesmo dia e mesmo valor em duas linhas: o id precisa distinguir as
        // duas, e precisa ser o MESMO na reimportação, senão a dedupe falha
        assertThat(primeira.get(0).getExternalId()).isNotEqualTo(primeira.get(1).getExternalId());
        assertThat(primeira).extracting(ParsedTransaction::getExternalId)
                .containsExactlyElementsOf(
                        segunda.stream().map(ParsedTransaction::getExternalId).toList());
    }

    @Test
    @DisplayName("Planilha .xls antiga recusa com a instrução do que fazer")
    void xlsAntigoRecusaComInstrucao() {
        // A mensagem crua do POI é indecifrável para quem exportou do banco
        byte[] ole2 = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1 };

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(ole2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".xlsx");
    }

    @Test
    @DisplayName("Planilha só com cabeçalho devolve lista vazia, sem erro")
    void planilhaSemLinhasNaoEErro() throws IOException {
        assertThat(parser.parse(planilha(
                new String[] { "Data", "Descrição", "Valor" }, new Object[][] {})))
                .isEmpty();
    }
}
