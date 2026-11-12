package br.com.economize.service.statement.parser;

import br.com.economize.config.StatementImportProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Os tetos de memória dos parsers: arquivo pequeno no disco que se expande
 * demais, PDF com páginas ou texto sem fim, CSV com linhas demais. Em todos
 * os casos a recusa é erro do CLIENTE (IllegalArgumentException, 400), nunca
 * 500 — e nunca importação pela metade.
 *
 * <p>Os tetos são minúsculos aqui, para nenhum teste alocar megabytes.
 */
@DisplayName("Parsers de extrato — tetos de memória")
class StatementParserLimitsTest {

    private final StatementImportProperties limits = new StatementImportProperties();
    private long maxEntrySizeOriginal;

    @BeforeEach
    void guardaTetoDoPoi() {
        maxEntrySizeOriginal = ZipSecureFile.getMaxEntrySize();
    }

    // O teto do POI é estático: sem restaurar, os outros testes da mesma JVM
    // herdariam o teto minúsculo deste
    @AfterEach
    void restauraTetoDoPoi() {
        ZipSecureFile.setMaxEntrySize(maxEntrySizeOriginal);
    }

    // --------------------------------------------------------------- XLSX

    private byte[] planilha(int linhas, boolean descricaoAleatoria) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Extrato");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Data");
            header.createCell(1).setCellValue("Valor");
            header.createCell(2).setCellValue("Descrição");
            for (int i = 1; i <= linhas; i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue("10/05/2026");
                row.createCell(1).setCellValue(-10.5);
                row.createCell(2).setCellValue(descricaoAleatoria ? UUID.randomUUID().toString() : "MERCADO");
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Uma planilha válida com uma entrada extra de zeros: poucos KB no disco,
     * megabytes expandida — a forma clássica de zip bomb.
     */
    private byte[] comBomba(byte[] xlsx, int bytesExpandidos) throws IOException {
        byte[] zeros = new byte[64 * 1024];
        return comEntradasExtras(xlsx, 1, zip -> {
            for (int i = 0; i < bytesExpandidos; i += zeros.length) zip.write(zeros);
        });
    }

    private interface Conteudo {
        void escrever(ZipOutputStream zip) throws IOException;
    }

    private byte[] comEntradasExtras(byte[] xlsx, int quantas, Conteudo conteudo) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(xlsx));
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                zip.putNextEntry(new ZipEntry(e.getName()));
                in.transferTo(zip);
                zip.closeEntry();
            }
            for (int i = 0; i < quantas; i++) {
                zip.putNextEntry(new ZipEntry("xl/extra" + i + ".bin"));
                conteudo.escrever(zip);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("XLSX com várias entradas, cada uma abaixo dos tetos do POI, é recusado pela SOMA")
    void xlsxSomaDasEntradasRecusada() throws IOException {
        limits.setMaxXlsxExpandedSize(DataSize.ofKilobytes(80));
        // bytes aleatórios: razão ~1:1, cada entrada de 20 KB passa no POI
        byte[] aleatorio = new byte[20 * 1024];
        new java.util.Random(42).nextBytes(aleatorio);
        byte[] arquivo = comEntradasExtras(planilha(1, false), 6, zip -> zip.write(aleatorio));
        XlsxParser parser = new XlsxParser(limits);

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(arquivo)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expande além do limite");
    }

    @Test
    @DisplayName("XLSX com razão de compressão absurda (zip bomb) é recusado como erro do cliente")
    void xlsxZipBombRecusado() throws IOException {
        // 4 MB de zeros: abaixo do teto de soma (5 MB), então quem recusa é a
        // trava de razão do POI (1:100) — a que já existia, e que antes saía 500
        byte[] bomba = comBomba(planilha(1, false), 4 * 1024 * 1024);
        assertThat(bomba.length).isLessThan(64 * 1024);
        XlsxParser parser = new XlsxParser(limits);

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(bomba)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expande além do limite");
    }

    @Test
    @DisplayName("XLSX que se expande além do teto por entrada é recusado mesmo com razão normal")
    void xlsxAcimaDoTetoExpandidoRecusado() throws IOException {
        // descrições aleatórias: compressão "honesta", que passa na trava de razão
        byte[] grande = planilha(3000, true);
        limits.setMaxXlsxExpandedSize(DataSize.ofKilobytes(50));
        XlsxParser parser = new XlsxParser(limits);

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(grande)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expande além do limite");
    }

    @Test
    @DisplayName("XLSX comum continua passando com os tetos padrão")
    void xlsxComumPassa() throws IOException {
        byte[] comum = planilha(200, true);
        List<ParsedTransaction> txs = new XlsxParser(limits).parse(new ByteArrayInputStream(comum));
        assertThat(txs).hasSize(200);
    }

    // ---------------------------------------------------------------- PDF

    private byte[] pdf(int paginas, String linha) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font fonte = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < paginas; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (linha == null) continue;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(fonte, 10);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(linha);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private PdfParser pdfParser() {
        return new PdfParser(new TxtParser(), limits);
    }

    @Test
    @DisplayName("PDF com mais páginas que o teto é recusado antes de extrair o texto")
    void pdfAcimaDoTetoDePaginasRecusado() throws IOException {
        limits.setMaxPdfPages(3);
        byte[] arquivo = pdf(4, null);

        assertThatThrownBy(() -> pdfParser().parse(new ByteArrayInputStream(arquivo)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4 páginas")
                .hasMessageContaining("limite é 3");
    }

    @Test
    @DisplayName("PDF exatamente no teto de páginas é lido normalmente")
    void pdfNoTetoDePaginasPassa() throws IOException {
        limits.setMaxPdfPages(3);
        byte[] arquivo = pdf(3, "10/05/2026 MERCADO TESTE -45,90");

        List<ParsedTransaction> txs = pdfParser().parse(new ByteArrayInputStream(arquivo));

        assertThat(txs).hasSize(3);
        assertThat(txs.get(0).getDescription()).contains("MERCADO");
    }

    @Test
    @DisplayName("PDF com texto acima do teto é recusado, mesmo com poucas páginas")
    void pdfAcimaDoTetoDeTextoRecusado() throws IOException {
        // uma página só, com mais glifos que o teto: o corte vem do stripper,
        // glifo a glifo, antes de a página inteira ir para a memória
        limits.setMaxPdfTextChars(40);
        byte[] arquivo = pdf(1, "10/05/2026 MERCADO TESTE -45,90 " + "X".repeat(200));

        assertThatThrownBy(() -> pdfParser().parse(new ByteArrayInputStream(arquivo)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("texto demais");
    }

    // ---------------------------------------------------------------- CSV

    @Test
    @DisplayName("CSV com linhas demais é recusado antes de montar a lista de linhas")
    void csvAcimaDoTetoDeLinhasRecusado() {
        limits.setMaxRows(5);
        StringBuilder csv = new StringBuilder("Data;Valor;Descrição\n");
        // 5 de teto + 20 de folga de preâmbulo: 26 linhas de dados passam do total
        for (int i = 0; i < 26; i++) csv.append("10/05/2026;-1,00;LINHA ").append(i).append('\n');
        CsvParser parser = new CsvParser(limits);

        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mais de 5 lançamentos");
    }

    // ---------------------------------------------------------------- OFX

    @Test
    @DisplayName("OFX com DOCTYPE e entidade externa não resolve a entidade: é texto, não XML")
    void ofxNaoResolveEntidadeExterna() {
        // O OfxParser lê por expressão regular, sem parser XML — não há onde uma
        // entidade ser expandida. Este teste trava isso: se um dia o OFX passar
        // por um parser XML, ele tem de nascer com DOCTYPE desligado
        String ofx = """
                <?xml version="1.0"?>
                <!DOCTYPE OFX [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <OFX>
                <STMTTRN>
                <TRNTYPE>DEBIT
                <DTPOSTED>20260101120000
                <TRNAMT>-10.00
                <FITID>TX-XXE
                <MEMO>&xxe;
                </STMTTRN>
                </OFX>
                """;

        List<ParsedTransaction> txs = new OfxParser().parse(
                new ByteArrayInputStream(ofx.getBytes(StandardCharsets.UTF_8)));

        assertThat(txs).hasSize(1);
        assertThat(txs.get(0).getDescription()).isEqualTo("&xxe;");
    }
}
