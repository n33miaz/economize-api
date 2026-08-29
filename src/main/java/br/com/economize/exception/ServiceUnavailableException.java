package br.com.economize.exception;

/**
 * O recurso existe e o pedido está correto, mas esta instalação não tem como
 * atendê-lo agora — falta configuração de ambiente do lado do servidor.
 *
 * <p>Nasceu no EC-107: sem {@code SECRET_ENCRYPTION_KEY} não existe cofre, e
 * gravar a chave de um usuário em claro está fora de cogitação. Dizer 400 seria
 * culpar o cliente por um pedido perfeitamente válido; 500 diria que algo
 * quebrou. 503 é a verdade: o serviço não está disponível nesta instalação.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
