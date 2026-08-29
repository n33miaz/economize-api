package br.com.economize.dto.connector;

/**
 * Token de curta duração que o app usa só para abrir o widget Pluggy Connect.
 * Não é a apiKey da aplicação — esta nunca sai da API.
 */
public record ConnectTokenResponse(String accessToken) {
}
