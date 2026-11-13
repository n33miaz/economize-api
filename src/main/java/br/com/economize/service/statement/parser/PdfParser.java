package br.com.economize.service.statement.parser;

import br.com.economize.config.StatementImportProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class PdfParser implements StatementParserStrategy {

    /**
     * Filtros de imagem: a extração de texto nunca os decodifica (imagem não
     * tem texto), e decodificá-los aqui só para medir custaria o que se quer
     * evitar.
     */
    private static final Set<COSName> IMAGE_FILTERS = Set.of(
            COSName.DCT_DECODE, COSName.DCT_DECODE_ABBREVIATION,
            COSName.JPX_DECODE, COSName.JBIG2_DECODE,
            COSName.CCITTFAX_DECODE, COSName.CCITTFAX_DECODE_ABBREVIATION);

    private final TxtParser txtParser;
    private final StatementImportProperties limits;

    public PdfParser(TxtParser txtParser, StatementImportProperties limits) {
        this.txtParser = txtParser;
        this.limits = limits;
        // na criação do bean: se a troca dos decodificadores falhar (PDFBox
        // de outra versão), a API não sobe em vez de aceitar PDF-bomba
        PdfDecodeBudget.install();
    }

    @Override
    public StatementFormat format() {
        return StatementFormat.PDF;
    }

    @Override
    public List<ParsedTransaction> parse(InputStream input) {
        byte[] bytes;
        try {
            bytes = input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler PDF", e);
        }
        // O orçamento vale da carga (Loader.loadPDF, onde o PDFBox já
        // descomprime object streams e xref streams) até o fim da extração,
        // e só nesta thread; ver PdfDecodeBudget
        try (PdfDecodeBudget budget = PdfDecodeBudget.open(
                limits.getMaxPdfStreamSize().toBytes(), limits.getMaxPdfDecodedTotal().toBytes())) {
            try {
                List<ParsedTransaction> parsed = extract(bytes);
                // o PDFBox pode ter engolido o estouro e seguido sem o fluxo
                if (budget.exceeded()) throw tooExpanded();
                return parsed;
            } catch (RuntimeException e) {
                // o estouro pode chegar embrulhado (IllegalStateException de
                // IOException, ou outra exceção do PDFBox): a marca decide
                if (budget.exceeded() && !isTooExpanded(e)) throw tooExpanded();
                throw e;
            }
        }
    }

    private IllegalArgumentException tooExpanded() {
        return new IllegalArgumentException(TOO_EXPANDED);
    }

    private static boolean isTooExpanded(RuntimeException e) {
        return e instanceof IllegalArgumentException && TOO_EXPANDED.equals(e.getMessage());
    }

    private static final String TOO_EXPANDED = "O PDF se expande além do limite aceito para um extrato. "
            + "Exporte um período menor ou use OFX/CSV.";

    private List<ParsedTransaction> extract(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            // Teto de páginas ANTES de extrair: a extração é a parte cara. Recusa
            // em vez de ler só o começo — ver StatementImportProperties
            int pages = document.getNumberOfPages();
            if (pages > limits.getMaxPdfPages()) {
                throw new IllegalArgumentException("O PDF tem " + pages + " páginas; o limite é "
                        + limits.getMaxPdfPages() + ". Exporte um período menor do extrato.");
            }
            checkDecodedStreams(document);
            PDFTextStripper stripper = new BoundedStripper(limits.getMaxPdfTextChars());
            // Ordenar por posição é o que mantém a linha do lançamento inteira:
            // sem isto, num layout de colunas o texto sai na ordem em que foi
            // desenhado, e data, valor e descrição chegam embaralhados
            stripper.setSortByPosition(true);
            // O stripper conta cada glifo ENQUANTO a página é processada: ele
            // junta a página inteira antes de escrever, e uma página só pode
            // desenhar milhões de glifos. O teto de páginas sozinho não bastaria
            String text = stripper.getText(document);

            // O Mercado Pago não exporta OFX nem CSV — PDF é o único formato que
            // o cliente consegue baixar. O layout dele quebra a descrição em três
            // linhas e o leitor genérico não dá conta; ver MercadoPagoPdfLayout.
            if (MercadoPagoPdfLayout.reconhece(text)) {
                List<ParsedTransaction> mercadoPago = MercadoPagoPdfLayout.parse(text);
                if (!mercadoPago.isEmpty()) return mercadoPago;
                // Extrato de mês parado: o documento foi lido inteiro e diz, ele
                // mesmo, que não houve movimento. Mandar exportar em OFX aqui é
                // culpar o arquivo por um mês em que nada aconteceu, e faz o
                // usuário procurar defeito onde não há.
                if (MercadoPagoPdfLayout.semMovimento(text)) {
                    throw new IllegalArgumentException(
                            "Este extrato não tem lançamentos no período — "
                                    + "o próprio arquivo informa entradas e saídas zeradas");
                }
                log.warn("PDF reconhecido como Mercado Pago, mas sem lançamentos legíveis — "
                        + "seguindo pelo leitor genérico");
            }

            List<ParsedTransaction> raw = txtParser.parse(
                    new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            // Layout de PDF raramente casa com o regex de linha do TXT: textos de
            // cabeçalho viram "transações" de descrição vazia (testado com extratos
            // reais — o período "12-08-2024 a 11-08-2026" virava duas linhas lixo).
            // Só passam adiante linhas com descrição minimamente identificável.
            List<ParsedTransaction> plausible = raw.stream()
                    .filter(tx -> countLetters(tx.getDescription()) >= 3)
                    .toList();
            int dropped = raw.size() - plausible.size();
            if (dropped > 0) {
                log.warn("PDF: {} linhas implausíveis descartadas de {}", dropped, raw.size());
            }
            if (plausible.isEmpty()) {
                throw new IllegalArgumentException(
                        "Não foi possível extrair transações confiáveis do PDF — "
                                + "exporte o extrato do banco em OFX (recomendado) ou CSV");
            }
            return plausible;
        } catch (TextLimitExceeded e) {
            throw new IllegalArgumentException("O PDF tem texto demais para um extrato (mais de "
                    + limits.getMaxPdfTextChars() + " caracteres). Exporte um período menor do extrato.");
        } catch (StreamLimitExceeded | PdfDecodeBudget.PdfDecodeLimitExceeded e) {
            throw tooExpanded();
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler PDF", e);
        }
    }

    /**
     * Conta os glifos à medida que o PDFBox os encontra, antes de guardá-los
     * na lista da página. A exceção é unchecked: o motor de conteúdo só trata
     * IOException de operador, então esta atravessa e interrompe a leitura.
     */
    private static final class BoundedStripper extends PDFTextStripper {
        private final int max;
        private int glyphs;

        BoundedStripper(int max) {
            this.max = max;
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            if (++glyphs > max) throw new TextLimitExceeded();
            super.processTextPosition(text);
        }
    }

    /**
     * Mede o tamanho DECODIFICADO de cada fluxo antes de o PDFBox ler qualquer
     * um. O PDFBox 3 descomprime cada fluxo inteiro num buffer no heap, sem
     * teto e sem passar pelo cache de fluxos: um PDF de centenas de KB com um
     * FlateDecode de zeros viraria centenas de MB antes do primeiro glifo.
     *
     * <p>A medição usa os mesmos filtros do PDFBox, mas escreve num destino
     * que só conta bytes e aborta ao passar do teto — o fluxo gigante nunca
     * existe inteiro na memória. Percorre TODOS os fluxos do arquivo (página,
     * formulário, fonte embutida), não só os conteúdos de página, porque
     * qualquer um deles é decodificado durante a extração. Imagem fica de fora:
     * a extração de texto não a decodifica.
     */
    private void checkDecodedStreams(PDDocument document) {
        long max = limits.getMaxPdfStreamSize().toBytes();
        COSDocument cos = document.getDocument();
        for (COSObjectKey key : new ArrayList<>(cos.getXrefTable().keySet())) {
            COSBase base = cos.getObjectFromPool(key).getObject();
            if (base instanceof COSStream stream && !COSName.IMAGE.equals(stream.getCOSName(COSName.SUBTYPE))) {
                checkStream(stream, max);
            }
        }
    }

    private void checkStream(COSStream stream, long max) {
        List<COSName> filters = filterNames(stream.getFilters());
        if (filters.isEmpty() || filters.stream().anyMatch(IMAGE_FILTERS::contains)) return;
        try {
            byte[] stage = null;
            for (int i = 0; i < filters.size(); i++) {
                // o original, sem o orçamento do documento: esta medição já tem
                // o próprio teto por fluxo e não deve gastar o total contando
                // de novo um fluxo que a extração vai decodificar
                Filter filter = PdfDecodeBudget.original(FilterFactory.INSTANCE.getFilter(filters.get(i)));
                boolean last = i == filters.size() - 1;
                // só os estágios intermediários guardam bytes (para o próximo
                // filtro), e nunca mais que o teto; o último só conta
                CappedOutput out = new CappedOutput(max, !last);
                try (InputStream in = stage == null
                        ? stream.createRawInputStream()
                        : new ByteArrayInputStream(stage)) {
                    filter.decode(in, out, stream, i);
                }
                stage = out.bytes();
            }
        } catch (IOException e) {
            // filtro desconhecido ou fluxo corrompido: o PDFBox tem a própria
            // tolerância para isso, e aqui o que interessa é só o tamanho
            log.debug("PDF: fluxo não medido ({})", e.getMessage());
        }
    }

    private static List<COSName> filterNames(COSBase filters) {
        List<COSName> names = new ArrayList<>();
        if (filters instanceof COSName name) {
            names.add(name);
        } else if (filters instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.getObject(i) instanceof COSName name) names.add(name);
            }
        }
        return names;
    }

    private static final class CappedOutput extends OutputStream {
        private final long max;
        private final ByteArrayOutputStream kept;
        private long count;

        CappedOutput(long max, boolean keep) {
            this.max = max;
            this.kept = keep ? new ByteArrayOutputStream() : null;
        }

        @Override
        public void write(int b) {
            if (++count > max) throw new StreamLimitExceeded();
            if (kept != null) kept.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            count += len;
            if (count > max) throw new StreamLimitExceeded();
            if (kept != null) kept.write(b, off, len);
        }

        byte[] bytes() {
            return kept == null ? null : kept.toByteArray();
        }
    }

    // unchecked: tem de atravessar o código dos filtros, que trata IOException
    private static final class StreamLimitExceeded extends RuntimeException {
        StreamLimitExceeded() {
            super(null, null, false, false);
        }
    }

    private static final class TextLimitExceeded extends RuntimeException {
        TextLimitExceeded() {
            super(null, null, false, false);
        }
    }

    private int countLetters(String value) {
        if (value == null) return 0;
        int letters = 0;
        for (char c : value.toCharArray()) {
            if (Character.isLetter(c)) letters++;
        }
        return letters;
    }
}
