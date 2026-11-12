package br.com.economize.exception;

import org.springframework.util.unit.DataSize;

/**
 * O arquivo enviado passa do teto que a instalação aceita. Vira 413 com a
 * mensagem escrita aqui.
 *
 * <p>É exceção própria, e não um handler global de
 * {@code DataBufferLimitException}, de propósito: o WebClient lança o mesmo
 * tipo quando a resposta de um provedor externo passa do limite do codec, e
 * aquilo é falha nossa/do provedor — chamar de 413 culparia o cliente. A
 * conversão acontece só na borda do upload, onde o excesso é do arquivo.
 */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(String message) {
        super(message);
    }

    /**
     * A mensagem única do 413, com o teto escrito — o corte no multipart e o
     * da junção dos bytes dizem a mesma coisa ao usuário.
     */
    public static PayloadTooLargeException forLimit(DataSize limit) {
        long bytes = limit.toBytes();
        String size = bytes >= 1024 * 1024 ? limit.toMegabytes() + " MB" : limit.toKilobytes() + " KB";
        return new PayloadTooLargeException("O arquivo passa do limite de " + size
                + ". Exporte um período menor do extrato.");
    }
}
