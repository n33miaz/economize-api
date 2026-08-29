package br.com.economize.exception;

import java.util.Map;

/**
 * Conflito de estado (HTTP 409): o recurso que se tenta criar colide com um que
 * já existe — diferente de requisição malformada (400), aqui o payload é válido
 * e o problema é o estado atual do sistema.
 *
 * <p>As {@code properties} viajam para o corpo do ProblemDetail: quando o
 * conflito aponta para um recurso concreto, a mensagem sozinha não basta — o
 * cliente precisa do id dele para oferecer a saída ("editar", "reativar") em vez
 * de deixar o usuário procurando a série na lista.
 */
public class ResourceConflictException extends RuntimeException {

    private final transient Map<String, Object> properties;

    public ResourceConflictException(String message) {
        this(message, Map.of());
    }

    public ResourceConflictException(String message, Map<String, Object> properties) {
        super(message);
        this.properties = Map.copyOf(properties);
    }

    public Map<String, Object> getProperties() {
        return properties;
    }
}
