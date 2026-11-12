package br.com.economize.service.statement.parser;

import br.com.economize.config.StatementImportProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O teto de bytes decodificados aplicado DENTRO dos decodificadores do
 * PDFBox ({@link PdfDecodeBudget}). O alvo são os object streams
 * ({@code /Type /ObjStm}), que o PDFBox descomprime já na carga
 * ({@code Loader.loadPDF}), antes de qualquer medição nossa.
 *
 * <p>As bombas daqui guardam o catálogo e a árvore de páginas DENTRO do
 * object stream: a carga não tem como evitar decodificá-lo. E cada uma é
 * conferida duas vezes — com teto folgado ela carrega (a bomba é um PDF
 * válido), com teto pequeno a CARGA falha. Sem isso, a medição posterior do
 * {@link PdfParser} faria os testes passarem mesmo sem a troca dos
 * decodificadores.
 *
 * <p>Tetos de KB contra bombas de poucos MB: nenhum teste aloca muito.
 */
@DisplayName("PDF — teto de descompressão nos decodificadores (object streams)")
class PdfObjectStreamLimitsTest {

    private static final int BOMBA = 4 * 1024 * 1024;
    private static final long TETO_PEQUENO = 256 * 1024;
    private static final long TETO_FOLGADO = 64L * 1024 * 1024;
    private static final String MSG_TETO = "PDF se expande além do limite";

    private final StatementImportProperties limits = new StatementImportProperties();

    private PdfParser pdfParser() {
        return new PdfParser(new TxtParser(), limits);
    }

    // ------------------------------------------------------------ montagem

    private static final String CATALOGO = "<< /Type /Catalog /Pages 3 0 R >>";
    private static final String PAGINAS = "<< /Type /Pages /Kids [4 0 R] /Count 1 >>";
    private static final String PAGINA = "<< /Type /Page /Parent 3 0 R /MediaBox [0 0 612 792] >>";

    /** Corpo decodificado de um object stream: cabeçalho, objetos e enchimento de zeros. */
    private static byte[] corpoObjStm(int[] numeros, String[] objetos) {
        StringBuilder cabecalho = new StringBuilder();
        StringBuilder corpo = new StringBuilder();
        for (int i = 0; i < numeros.length; i++) {
            cabecalho.append(numeros[i]).append(' ').append(corpo.length()).append(' ');
            corpo.append(objetos[i]).append('\n');
        }
        return (cabecalho + "\n" + corpo).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static int first(int[] numeros, String[] objetos) {
        StringBuilder cabecalho = new StringBuilder();
        int pos = 0;
        for (int i = 0; i < numeros.length; i++) {
            cabecalho.append(numeros[i]).append(' ').append(pos).append(' ');
            pos += objetos[i].length() + 1;
        }
        return cabecalho.length() + 1;
    }

    /** Deflate do prefixo seguido de {@code zeros} bytes zero, sem montar o todo na memória. */
    private static byte[] deflate(byte[] prefixo, int zeros) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream def = new DeflaterOutputStream(out)) {
            def.write(prefixo);
            byte[] bloco = new byte[64 * 1024];
            for (int restante = zeros; restante > 0; restante -= bloco.length) {
                def.write(bloco, 0, Math.min(bloco.length, restante));
            }
        }
        return out.toByteArray();
    }

    private static byte[] lzw(byte[] prefixo, int zeros) throws IOException {
        ByteArrayOutputStream bruto = new ByteArrayOutputStream();
        bruto.write(prefixo);
        bruto.write(new byte[zeros]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new ByteArrayInputStream(bruto.toByteArray())) {
            FilterFactory.INSTANCE.getFilter(COSName.LZW_DECODE).encode(in, out, new COSDictionary(), 0);
        }
        return out.toByteArray();
    }

    /**
     * PDF 1.5 escrito à mão: objetos de topo, objetos comprimidos em object
     * streams e uma tabela xref em fluxo (a única que endereça objeto
     * comprimido).
     */
    private static final class MontadorPdf {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final List<long[]> xref = new ArrayList<>(); // {num, tipo, campo2, campo3}

        MontadorPdf() {
            escrever("%PDF-1.7\n%âãÏÓ\n");
        }

        MontadorPdf objeto(int num, String corpo) {
            xref.add(new long[]{num, 1, out.size(), 0});
            escrever(num + " 0 obj\n" + corpo + "\nendobj\n");
            return this;
        }

        MontadorPdf fluxo(int num, String dicionario, byte[] dados) {
            xref.add(new long[]{num, 1, out.size(), 0});
            escrever(num + " 0 obj\n" + dicionario + "\nstream\n");
            out.writeBytes(dados);
            escrever("\nendstream\nendobj\n");
            return this;
        }

        MontadorPdf comprimido(int num, int objStm, int indice) {
            xref.add(new long[]{num, 2, objStm, indice});
            return this;
        }

        byte[] fechar(int raiz) {
            int numXref = xref.stream().mapToInt(e -> (int) e[0]).max().orElse(0) + 1;
            long offsetXref = out.size();
            xref.add(new long[]{numXref, 1, offsetXref, 0});
            long[][] linhas = new long[numXref + 1][];
            for (long[] e : xref) linhas[(int) e[0]] = e;
            ByteArrayOutputStream dados = new ByteArrayOutputStream();
            for (long[] e : linhas) {
                long tipo = e == null ? 0 : e[1];
                long c2 = e == null ? 0 : e[2];
                long c3 = e == null ? 65535 : e[3];
                dados.write((int) tipo);
                for (int s = 24; s >= 0; s -= 8) dados.write((int) (c2 >> s));
                dados.write((int) (c3 >> 8));
                dados.write((int) c3);
            }
            byte[] tabela = dados.toByteArray();
            escrever(numXref + " 0 obj\n<< /Type /XRef /Size " + (numXref + 1) + " /W [1 4 2] /Root "
                    + raiz + " 0 R /Length " + tabela.length + " >>\nstream\n");
            out.writeBytes(tabela);
            escrever("\nendstream\nendobj\nstartxref\n" + offsetXref + "\n%%EOF\n");
            return out.toByteArray();
        }

        private void escrever(String s) {
            out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    private static final int[] NUMS = {2, 3, 4};
    private static final String[] OBJS = {CATALOGO, PAGINAS, PAGINA};

    /** Catálogo, páginas e página num object stream só (objeto 1), com {@code zeros} de enchimento. */
    private static byte[] bombaObjStm(String filtroELength, byte[] dadosCodificados, String extras) {
        MontadorPdf pdf = new MontadorPdf();
        pdf.fluxo(1, "<< /Type /ObjStm /N 3 /First " + first(NUMS, OBJS) + " " + filtroELength + " >>",
                dadosCodificados);
        if (extras != null) pdf.objeto(7, extras);
        for (int i = 0; i < NUMS.length; i++) pdf.comprimido(NUMS[i], 1, i);
        return pdf.fechar(2);
    }

    private static byte[] bombaFlate(String lengthOverride) throws IOException {
        byte[] dados = deflate(corpoObjStm(NUMS, OBJS), BOMBA);
        String length = lengthOverride != null ? lengthOverride : String.valueOf(dados.length);
        return bombaObjStm("/Filter /FlateDecode /Length " + length, dados, null);
    }

    /** Com teto folgado, a bomba carrega: é um PDF válido, e o catálogo está mesmo no ObjStm. */
    private static void confereQueCarregaComTetoFolgado(byte[] pdf) throws IOException {
        try (PdfDecodeBudget ignored = PdfDecodeBudget.open(TETO_FOLGADO, TETO_FOLGADO);
             PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    /** Com teto pequeno, a CARGA falha — antes da medição do PdfParser existir. */
    private static void confereQueCargaFalha(byte[] pdf) {
        try (PdfDecodeBudget budget = PdfDecodeBudget.open(TETO_PEQUENO, TETO_FOLGADO)) {
            assertThatThrownBy(() -> {
                try (PDDocument doc = Loader.loadPDF(pdf)) {
                    doc.getNumberOfPages();
                }
            }).isNotNull();
            assertThat(budget.exceeded()).isTrue();
        }
    }

    private void confereRecusa(byte[] pdf) {
        limits.setMaxPdfStreamSize(DataSize.ofBytes(TETO_PEQUENO));
        assertThatThrownBy(() -> pdfParser().parse(new ByteArrayInputStream(pdf)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MSG_TETO);
    }

    private static boolean contem(byte[] pdf, String trecho) {
        return new String(pdf, StandardCharsets.ISO_8859_1).contains(trecho);
    }

    // --------------------------------------------------------------- testes

    @Test
    @DisplayName("os decodificadores que expandem estão trocados no FilterFactory")
    void filtrosTrocados() throws IOException {
        pdfParser();
        for (COSName nome : List.of(COSName.FLATE_DECODE, COSName.FLATE_DECODE_ABBREVIATION,
                COSName.LZW_DECODE, COSName.RUN_LENGTH_DECODE, COSName.ASCII85_DECODE,
                COSName.ASCII_HEX_DECODE)) {
            var filtro = FilterFactory.INSTANCE.getFilter(nome);
            assertThat(PdfDecodeBudget.original(filtro)).as(nome.getName()).isNotSameAs(filtro);
        }
        // imagem fica com o original: a extração de texto não a decodifica
        var dct = FilterFactory.INSTANCE.getFilter(COSName.DCT_DECODE);
        assertThat(PdfDecodeBudget.original(dct)).isSameAs(dct);
    }

    @Test
    @DisplayName("(a) ObjStm-bomba em FlateDecode é recusado já na carga")
    void objStmBombaFlate() throws IOException {
        byte[] pdf = bombaFlate(null);
        assertThat(pdf.length).isLessThan(16 * 1024);

        confereQueCarregaComTetoFolgado(pdf);
        confereQueCargaFalha(pdf);
        confereRecusa(pdf);
    }

    @Test
    @DisplayName("(b) /Length mentiroso, curto ou enorme, não fura o teto")
    void objStmBombaLengthMentiroso() throws IOException {
        for (String length : List.of("10", "2147483647")) {
            byte[] pdf = bombaFlate(length);
            confereQueCarregaComTetoFolgado(pdf);
            confereQueCargaFalha(pdf);
            confereRecusa(pdf);
        }
    }

    @Test
    @DisplayName("(b) filtro indireto (/Filter 7 0 R) não fura o teto")
    void objStmBombaFiltroIndireto() throws IOException {
        byte[] dados = deflate(corpoObjStm(NUMS, OBJS), BOMBA);
        byte[] pdf = bombaObjStm("/Filter 7 0 R /Length " + dados.length, dados, "/FlateDecode");

        confereQueCarregaComTetoFolgado(pdf);
        confereQueCargaFalha(pdf);
        confereRecusa(pdf);
    }

    @Test
    @DisplayName("(b) cadeia [/ASCIIHexDecode /FlateDecode] não fura o teto")
    void objStmBombaCadeia() throws IOException {
        byte[] hex = (HexFormat.of().formatHex(deflate(corpoObjStm(NUMS, OBJS), BOMBA)) + ">")
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] pdf = bombaObjStm("/Filter [/ASCIIHexDecode /FlateDecode] /Length " + hex.length, hex, null);

        confereQueCarregaComTetoFolgado(pdf);
        confereQueCargaFalha(pdf);
        confereRecusa(pdf);
    }

    @Test
    @DisplayName("(b) ObjStm-bomba em LZWDecode é recusado")
    void objStmBombaLzw() throws IOException {
        byte[] dados = lzw(corpoObjStm(NUMS, OBJS), BOMBA);
        assertThat(dados.length).isLessThan(64 * 1024);
        byte[] pdf = bombaObjStm("/Filter /LZWDecode /Length " + dados.length, dados, null);

        confereQueCarregaComTetoFolgado(pdf);
        confereQueCargaFalha(pdf);
        confereRecusa(pdf);
    }

    @Test
    @DisplayName("(b) vários ObjStm, cada um abaixo do teto por fluxo, somam acima do total e são recusados")
    void variosObjStmSomamAcimaDoTotal() throws IOException {
        MontadorPdf montador = new MontadorPdf();
        int[] objStms = {1, 5, 6};
        for (int i = 0; i < NUMS.length; i++) {
            int[] n = {NUMS[i]};
            String[] o = {OBJS[i]};
            byte[] dados = deflate(corpoObjStm(n, o), 200 * 1024);
            montador.fluxo(objStms[i], "<< /Type /ObjStm /N 1 /First " + first(n, o)
                    + " /Filter /FlateDecode /Length " + dados.length + " >>", dados);
            montador.comprimido(NUMS[i], objStms[i], 0);
        }
        byte[] pdf = montador.fechar(2);
        limits.setMaxPdfStreamSize(DataSize.ofBytes(TETO_PEQUENO));
        limits.setMaxPdfDecodedTotal(DataSize.ofBytes(400 * 1024));

        assertThatThrownBy(() -> pdfParser().parse(new ByteArrayInputStream(pdf)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MSG_TETO);
    }

    @Test
    @DisplayName("(b) PDF cifrado com ObjStm-bomba: a decifragem vem antes do filtro, e o teto vale")
    void pdfCifradoComObjStmBomba() throws IOException {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            // a string vai para o object stream junto com a página; zeros
            // comprimem a quase nada, e o ObjStm inteiro é cifrado como fluxo
            page.getCOSObject().setItem(COSName.getPDFName("Lixo"), new COSString(new byte[BOMBA]));
            StandardProtectionPolicy politica = new StandardProtectionPolicy("dono", "", new AccessPermission());
            politica.setEncryptionKeyLength(128);
            doc.protect(politica);
            doc.save(out);
            pdf = out.toByteArray();
        }
        assertThat(contem(pdf, "/Encrypt")).isTrue();
        assertThat(contem(pdf, "/ObjStm")).isTrue();
        assertThat(pdf.length).isLessThan(64 * 1024);

        confereQueCarregaComTetoFolgado(pdf);
        confereQueCargaFalha(pdf);
        confereRecusa(pdf);
    }

    @Test
    @DisplayName("(c) PDF comum, com ObjStm pequeno, é lido igual")
    void pdfComumComObjStm() throws IOException {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(50, 700);
                cs.showText("10/05/2026 MERCADO TESTE -45,90");
                cs.endText();
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        assertThat(contem(pdf, "/ObjStm")).isTrue();
        limits.setMaxPdfStreamSize(DataSize.ofBytes(TETO_PEQUENO));

        List<ParsedTransaction> txs = pdfParser().parse(new ByteArrayInputStream(pdf));

        assertThat(txs).hasSize(1);
        assertThat(txs.get(0).getDescription()).contains("MERCADO");
    }

    @Test
    @DisplayName("(d) imagem FlateDecode maior que o teto continua aceita: a extração de texto não a decodifica")
    void imagemFlateGrandeAceita() throws IOException {
        byte[] pdf;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            // 1000x1000 RGB = 3 MB decodificados, contra teto de 1 MB por fluxo
            BufferedImage img = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.LIGHT_GRAY);
            g.fillRect(0, 0, 1000, 1000);
            g.dispose();
            PDImageXObject imagem = LosslessFactory.createFromImage(doc, img);
            assertThat(imagem.getCOSObject().getFilters()).isEqualTo(COSName.FLATE_DECODE);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(imagem, 0, 0, 612, 792);
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(50, 700);
                cs.showText("10/05/2026 MERCADO TESTE -45,90");
                cs.endText();
            }
            doc.save(out);
            pdf = out.toByteArray();
        }
        limits.setMaxPdfStreamSize(DataSize.ofMegabytes(1));
        limits.setMaxPdfDecodedTotal(DataSize.ofMegabytes(1));

        List<ParsedTransaction> txs = pdfParser().parse(new ByteArrayInputStream(pdf));

        assertThat(txs).hasSize(1);
        assertThat(txs.get(0).getDescription()).contains("MERCADO");
    }

    @Test
    @DisplayName("(e) fora do parse, sem orçamento na thread, o decodificador não limita")
    void foraDoParseNaoLimita() throws IOException {
        pdfParser(); // garante a troca dos decodificadores
        COSStream fluxo = new COSStream();
        try (OutputStream os = fluxo.createOutputStream(COSName.FLATE_DECODE)) {
            os.write(new byte[BOMBA]);
        }
        try (InputStream in = fluxo.createInputStream()) {
            assertThat(in.readAllBytes()).hasSize(BOMBA);
        }
        // e depois de um parse recusado o orçamento não fica preso à thread
        limits.setMaxPdfStreamSize(DataSize.ofBytes(TETO_PEQUENO));
        byte[] bomba = bombaFlate(null);
        assertThatThrownBy(() -> pdfParser().parse(new ByteArrayInputStream(bomba)))
                .hasMessageContaining(MSG_TETO);
        assertThatCode(() -> {
            try (InputStream in = fluxo.createInputStream()) {
                assertThat(in.readAllBytes()).hasSize(BOMBA);
            }
        }).doesNotThrowAnyException();
        fluxo.close();
    }
}
