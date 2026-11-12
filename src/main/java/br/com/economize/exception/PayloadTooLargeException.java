package br.com.economize.exception;

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
}
