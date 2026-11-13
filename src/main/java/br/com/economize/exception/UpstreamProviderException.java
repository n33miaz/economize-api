package br.com.economize.exception;

/**
 * O provedor externo respondeu, mas sem o que o contrato dele promete (ex.: a
 * Pluggy devolve 200 sem {@code apiKey}). Antes era {@code IllegalStateException}
 * e caía no handler genérico como 500, como se o defeito fosse nosso.
 *
 * <p>A mensagem é para o LOG (pode citar variável de ambiente); o handler
 * responde 502 com texto fixo, sem ela.
 */
public class UpstreamProviderException extends RuntimeException {

    public UpstreamProviderException(String message) {
        super(message);
    }
}
