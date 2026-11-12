package br.com.economize.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * Tetos da importação de extrato por arquivo. Existem por causa da memória, não
 * por regra de negócio: a API roda com 192 MB de heap e o upload era juntado
 * inteiro num {@code byte[]} antes do parser — um PDF ou XLSX gigante (ou um
 * ZIP muito comprimido, que é o que um XLSX é por dentro) enviado algumas
 * vezes derrubava o serviço para todo mundo.
 *
 * <p>Os padrões ficam bem acima de qualquer extrato real: arquivo de banco tem
 * de dezenas de KB a poucos MB, e dois anos de conta corrente são uns 5 mil
 * lançamentos. Mudar exige só property ({@code economize.statement.*}), sem
 * deploy de código.
 *
 * <p>Ultrapassar qualquer teto RECUSA o arquivo; nunca importa um pedaço. A
 * importação é idempotente por hash: um extrato cortado gravaria o hash, e o
 * reenvio do mesmo arquivo responderia "já importado" com dados parciais para
 * sempre.
 */
@Data
@Component
@ConfigurationProperties(prefix = "economize.statement")
public class StatementImportProperties {

    /**
     * Tamanho máximo do arquivo enviado. É o mesmo valor que limita a parte
     * do multipart no {@code application.properties}, e é conferido de novo
     * ao juntar os bytes, para o caso de o multipart estar configurado diferente.
     */
    private DataSize maxUploadSize = DataSize.ofMegabytes(10);

    /** Lançamentos por arquivo. Acima disso não é extrato, é ataque ou engano. */
    private int maxRows = 20_000;

    /**
     * Páginas de PDF. O extrato anual do Mercado Pago (1.682 lançamentos no
     * app) passa de 100 páginas; 200 dá folga. O custo real de cada página é
     * contido pelos tetos de glifos e de fluxo decodificado, não por este.
     */
    private int maxPdfPages = 200;

    /**
     * Tamanho DECODIFICADO máximo de cada fluxo do PDF (conteúdo de página,
     * formulário, fonte embutida). O PDFBox descomprime cada fluxo inteiro no
     * heap; o conteúdo de uma página densa fica na casa das centenas de KB, e
     * 16 MB ainda aceita uma fonte embutida completa.
     */
    private DataSize maxPdfStreamSize = DataSize.ofMegabytes(16);

    /**
     * Soma dos bytes DECODIFICADOS de todos os fluxos que o PDFBox
     * descomprime durante a leitura de um PDF (object streams, xref, fontes,
     * formulários). O teto por fluxo sozinho não basta: dezenas de object
     * streams logo abaixo de 16 MB cada caberiam num upload de 10 MB, e o
     * PDFBox guarda os objetos lidos de cada um. Um extrato real soma poucos
     * MB (o conteúdo de página com FlateDecode simples é lido em fluxo, sem
     * buffer, e nem entra na conta); 64 MB dá folga a fontes embutidas
     * inteiras e ainda deixa dois terços do heap livres.
     */
    private DataSize maxPdfDecodedTotal = DataSize.ofMegabytes(64);

    /**
     * Caracteres (glifos) de texto extraído do PDF. Um PDF pequeno pode
     * desenhar texto sem fim (fluxos comprimidos, repetição); o teto é contado
     * glifo a glifo, então vale mesmo dentro de uma página só. 200 páginas
     * densas dão perto de 1 milhão de caracteres.
     */
    private int maxPdfTextChars = 1_000_000;

    /**
     * Tamanho EXPANDIDO máximo do XLSX, somando todas as entradas do ZIP. O POI
     * monta a planilha inteira no heap (XSSF), então o que custa memória é o
     * XML descomprimido, não o arquivo enviado. Medido em 12/11/2026: 0,9 MB
     * expandido (5 mil lançamentos, dois anos de conta) ocupa ~12 MB de heap;
     * 7,4 MB expandidos ocupam ~112 MB — mais da metade dos 192 MB. 5 MB fica
     * em ~75 MB, e ainda cobre uns 25 mil lançamentos de três colunas.
     */
    private DataSize maxXlsxExpandedSize = DataSize.ofMegabytes(5);

    /**
     * Tamanho expandido máximo de CADA entrada, conferido pelo próprio POI
     * enquanto lê. Parece redundante com a soma acima, mas não é: a soma é
     * medida com o leitor de ZIP do Java e o POI usa outro (commons-compress);
     * um ZIP forjado para os dois enxergarem entradas diferentes escaparia da
     * soma e ainda bateria aqui.
     */
    private DataSize maxXlsxEntrySize = DataSize.ofMegabytes(5);
}
