package br.com.economize.exception;

/**
 * Recurso inexistente PARA QUEM PEDE (HTTP 404).
 *
 * <p>Também é a resposta quando o recurso existe mas pertence a outro usuário:
 * 403 confirmaria a existência do id e transformaria a rota num oráculo de
 * enumeração — o mesmo vazamento que o EC-037 corrigiu. Do ponto de vista do
 * token, o que não é dele simplesmente não existe.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
