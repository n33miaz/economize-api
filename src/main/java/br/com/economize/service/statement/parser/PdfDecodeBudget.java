package br.com.economize.service.statement.parser;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.filter.DecodeOptions;
import org.apache.pdfbox.filter.DecodeResult;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orçamento de bytes DECODIFICADOS para a leitura de um extrato em PDF,
 * aplicado dentro dos próprios decodificadores do PDFBox.
 *
 * <p>Por que no decodificador: o PDFBox 3 descomprime fluxos inteiros num
 * buffer no heap, sem teto — e alguns deles ANTES de o nosso código ver o
 * documento. Os object streams ({@code /Type /ObjStm}, PDF 1.5+) e os xref
 * streams são decodificados dentro do {@code Loader.loadPDF}; um PDF de
 * poucos KB com um ObjStm de FlateDecode muito expandido estouraria os 192 MB
 * de heap, e o serviço (com {@code -XX:+ExitOnOutOfMemoryError}) cairia para
 * todos. Medir os bytes crus antes da carga não serve: {@code /Length}
 * mentiroso, filtro indireto, cadeia de filtros, LZW e PDF cifrado (o PDFBox
 * decifra e só depois descomprime) furam qualquer pré-varredura. O
 * decodificador é o único ponto por onde TODO fluxo passa já decifrado, com
 * o filtro resolvido, um estágio da cadeia de cada vez.
 *
 * <p>Fora de um {@link #open orçamento aberto} na thread, os decodificadores
 * trocados repassam ao original sem contar nada: outros usos do PDFBox no
 * processo não mudam de comportamento.
 *
 * <p>Ficam de fora os filtros de imagem (DCT, JPX, JBIG2, CCITT): a extração
 * de texto não decodifica imagem ({@code DrawObject} retorna cedo para
 * XObject de imagem, e o {@code PDImageXObject} só decodifica na carga se for
 * JPX), então um escaneado grande segue aceito. E o {@code Crypt}, que não
 * expande. Imagem com FlateDecode passa pelo decodificador trocado só se for
 * decodificada — o que a extração de texto não faz; imagem embutida no
 * conteúdo ({@code BI ... ID ... EI}) é pequena por natureza e já está dentro
 * do teto do fluxo de conteúdo.
 */
final class PdfDecodeBudget implements AutoCloseable {

    /**
     * Todos os filtros que não são de imagem. Flate e LZW são os que expandem
     * centenas de vezes; RunLength expande até 64x (2 bytes viram 128) e o
     * ASCII85 até 4x ({@code z}) — numa cadeia {@code [/FlateDecode
     * /RunLengthDecode]}, o estágio final multiplicaria o que o Flate já
     * entregou no teto. Contar todos os estágios fecha a cadeia inteira.
     */
    private static final List<COSName> LIMITED = List.of(
            COSName.FLATE_DECODE, COSName.FLATE_DECODE_ABBREVIATION,
            COSName.LZW_DECODE, COSName.LZW_DECODE_ABBREVIATION,
            COSName.RUN_LENGTH_DECODE, COSName.RUN_LENGTH_DECODE_ABBREVIATION,
            COSName.ASCII85_DECODE, COSName.ASCII85_DECODE_ABBREVIATION,
            COSName.ASCII_HEX_DECODE, COSName.ASCII_HEX_DECODE_ABBREVIATION);

    private static final ThreadLocal<PdfDecodeBudget> CURRENT = new ThreadLocal<>();

    static {
        install();
    }

    private final long maxPerStream;
    private final long maxTotal;
    private final PdfDecodeBudget previous;
    private long total;
    private boolean exceeded;

    private PdfDecodeBudget(long maxPerStream, long maxTotal, PdfDecodeBudget previous) {
        this.maxPerStream = maxPerStream;
        this.maxTotal = maxTotal;
        this.previous = previous;
    }

    /**
     * Liga o orçamento na thread atual até o {@link #close()}. O parse do
     * extrato é síncrono (uma chamada bloqueante, numa thread só), então a
     * thread é o escopo certo: o que o PDFBox decodificar no meio dele conta.
     */
    static PdfDecodeBudget open(long maxPerStream, long maxTotal) {
        PdfDecodeBudget budget = new PdfDecodeBudget(maxPerStream, maxTotal, CURRENT.get());
        CURRENT.set(budget);
        return budget;
    }

    /**
     * O teto já foi ultrapassado nesta leitura? O PDFBox é leniente: em
     * alguns caminhos ele engole a falha de um fluxo e segue sem ele. A
     * marca fica, então quem abriu o orçamento recusa mesmo assim — nunca
     * um documento lido pela metade.
     */
    boolean exceeded() {
        return exceeded;
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    /**
     * Decodificador original por trás do trocado. A medição prévia do
     * {@link PdfParser} usa o original: ela já tem o próprio teto por fluxo
     * e não pode gastar o orçamento total do documento contando o mesmo
     * fluxo duas vezes.
     */
    static Filter original(Filter filter) {
        return filter instanceof Limited limited ? limited.delegate : filter;
    }

    /**
     * Troca os decodificadores no {@link FilterFactory}. O mapa é privado e
     * sem API de registro, então a troca é por reflexão — mas só lê o campo:
     * o mapa é um {@code HashMap} comum (só a referência é final) e aqui se
     * substitui o VALOR de chaves que já existem, sem mudança estrutural.
     * O PDFBox está no classpath (módulo sem nome), então o
     * {@code setAccessible} vale no Java 17 sem {@code --add-opens}. Idempotente.
     *
     * <p>Se uma versão futura do PDFBox mudar o campo, a falha é na subida
     * (e no teste que confere a troca), não um PDF-bomba aceito em silêncio.
     */
    @SuppressWarnings("unchecked")
    static synchronized void install() {
        try {
            Field field = FilterFactory.class.getDeclaredField("filters");
            field.setAccessible(true);
            Map<COSName, Filter> filters = (Map<COSName, Filter>) field.get(FilterFactory.INSTANCE);
            // um embrulho por INSTÂNCIA original, não por nome: /FlateDecode e
            // /Fl são o mesmo objeto no PDFBox, e a decodificação em cadeia
            // descarta filtros repetidos por identidade — dois embrulhos
            // fariam [/FlateDecode /Fl] descomprimir duas vezes e falhar
            Map<Filter, Limited> wrapped = new IdentityHashMap<>();
            for (COSName name : LIMITED) {
                Filter current = filters.get(name);
                if (current == null) {
                    throw new IllegalStateException("PDFBox sem o filtro " + name.getName());
                }
                if (!(current instanceof Limited)) {
                    filters.put(name, wrapped.computeIfAbsent(current, Limited::new));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Não foi possível limitar os decodificadores do PDFBox", e);
        }
    }

    private void count(long bytes, long streamCount) {
        total += bytes;
        if (streamCount > maxPerStream || total > maxTotal) {
            exceeded = true;
            throw new PdfDecodeLimitExceeded();
        }
    }

    /**
     * Repassa ao decodificador original, só trocando o destino por um que
     * conta. O original escreve em pedaços enquanto descomprime, então o
     * corte acontece no primeiro pedaço acima do teto — o fluxo nunca existe
     * inteiro na memória.
     */
    private static final class Limited extends Filter {
        private final Filter delegate;

        Limited(Filter delegate) {
            this.delegate = delegate;
        }

        @Override
        public DecodeResult decode(InputStream in, OutputStream out, COSDictionary params, int index)
                throws IOException {
            return delegate.decode(in, counted(out), params, index);
        }

        @Override
        public DecodeResult decode(InputStream in, OutputStream out, COSDictionary params, int index,
                                   DecodeOptions options) throws IOException {
            return delegate.decode(in, counted(out), params, index, options);
        }

        // A escrita (salvar PDF) também passa pelo FilterFactory. O encode
        // protegido do original não é visível daqui; o público delega a ele
        // (o índice só é usado para achar parâmetros, e o Flate não os usa)
        @Override
        protected void encode(InputStream in, OutputStream out, COSDictionary params) throws IOException {
            delegate.encode(in, out, params, 0);
        }

        private static OutputStream counted(OutputStream out) {
            PdfDecodeBudget budget = CURRENT.get();
            return budget == null ? out : new Counting(out, budget);
        }
    }

    private static final class Counting extends FilterOutputStream {
        private final PdfDecodeBudget budget;
        private long count;

        Counting(OutputStream out, PdfDecodeBudget budget) {
            super(out);
            this.budget = budget;
        }

        @Override
        public void write(int b) throws IOException {
            budget.count(1, ++count);
            out.write(b);
        }

        // sem isto o FilterOutputStream escreveria byte a byte
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            count += len;
            budget.count(len, count);
            out.write(b, off, len);
        }
    }

    /**
     * Unchecked de propósito: o COSParser trata {@code IOException} na
     * leitura de object stream (segue sem ele, leniente) e os filtros também;
     * esta atravessa e interrompe a carga. Onde mesmo assim for engolida,
     * {@link #exceeded()} guarda o fato.
     */
    static final class PdfDecodeLimitExceeded extends RuntimeException {
        PdfDecodeLimitExceeded() {
            super("Fluxo do PDF decodificado acima do teto", null, false, false);
        }
    }
}
