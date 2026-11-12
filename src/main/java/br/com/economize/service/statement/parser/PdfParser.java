package br.com.economize.service.statement.parser;

import br.com.economize.config.StatementImportProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.List;

@Slf4j
@Component
public class PdfParser implements StatementParserStrategy {

    /**
     * Cache dos fluxos do PDF: até 4 MB no heap, o resto em arquivo temporário,
     * e no máximo 64 MB no total. Sem teto, um PDF pequeno com fluxos
     * comprimidos que se expandem muito podia ocupar o heap inteiro (192 MB).
     */
    private static final long STREAM_CACHE_MEMORY = 4L * 1024 * 1024;
    private static final long STREAM_CACHE_TOTAL = 64L * 1024 * 1024;

    private final TxtParser txtParser;
    private final StatementImportProperties limits;

    public PdfParser(TxtParser txtParser, StatementImportProperties limits) {
        this.txtParser = txtParser;
        this.limits = limits;
    }

    @Override
    public StatementFormat format() {
        return StatementFormat.PDF;
    }

    @Override
    public List<ParsedTransaction> parse(InputStream input) {
        try (PDDocument document = Loader.loadPDF(input.readAllBytes(), "", null, null,
                MemoryUsageSetting.setupMixed(STREAM_CACHE_MEMORY, STREAM_CACHE_TOTAL).streamCache)) {
            // Teto de páginas ANTES de extrair: a extração é a parte cara. Recusa
            // em vez de ler só o começo — ver StatementImportProperties
            int pages = document.getNumberOfPages();
            if (pages > limits.getMaxPdfPages()) {
                throw new IllegalArgumentException("O PDF tem " + pages + " páginas; o limite é "
                        + limits.getMaxPdfPages() + ". Exporte um período menor do extrato.");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            // Ordenar por posição é o que mantém a linha do lançamento inteira:
            // sem isto, num layout de colunas o texto sai na ordem em que foi
            // desenhado, e data, valor e descrição chegam embaralhados
            stripper.setSortByPosition(true);
            // writeText num escritor com teto, e não getText: o teto de páginas
            // não basta, porque uma página só pode desenhar texto sem fim
            BoundedWriter out = new BoundedWriter(limits.getMaxPdfTextChars());
            stripper.writeText(document, out);
            String text = out.toString();

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
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler PDF", e);
        }
    }

    /**
     * Junta o texto e para de aceitar ao passar do teto. A falha é unchecked de
     * propósito: o motor de conteúdo do PDFBox engole algumas IOException de
     * operador para seguir lendo PDF defeituoso, e o corte aqui não pode ser
     * engolido.
     */
    private static final class BoundedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int max;

        BoundedWriter(int max) {
            this.max = max;
        }

        @Override
        public void write(char[] cbuf, int off, int len) {
            if (text.length() + len > max) throw new TextLimitExceeded();
            text.append(cbuf, off, len);
        }

        @Override
        public void write(String str, int off, int len) {
            if (text.length() + len > max) throw new TextLimitExceeded();
            text.append(str, off, off + len);
        }

        @Override
        public void flush() {
            // nada a descarregar: o texto fica no StringBuilder
        }

        @Override
        public void close() {
            // idem
        }

        @Override
        public String toString() {
            return text.toString();
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
