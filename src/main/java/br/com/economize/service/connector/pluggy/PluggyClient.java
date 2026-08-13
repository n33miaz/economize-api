package br.com.economize.service.connector.pluggy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cliente mínimo da API do Pluggy (meu.pluggy.ai) — ADR-011. O usuário conecta
 * as contas dele no Meu Pluggy e traz clientId/clientSecret/itemIds do
 * dashboard; a API só lê. Chamadas blocantes de propósito: o sync roda no
 * boundedElastic como todo o pipeline de importação.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "economize.pluggy.enabled", havingValue = "true")
public class PluggyClient {

    private static final int PAGE_SIZE = 500;

    private final WebClient webClient;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;

    public PluggyClient(WebClient webClient,
                        @Value("${economize.pluggy.base-url}") String baseUrl,
                        @Value("${economize.pluggy.client-id}") String clientId,
                        @Value("${economize.pluggy.client-secret}") String clientSecret) {
        this.webClient = webClient;
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    /** POST /auth — apiKey de curta duração usada nas demais chamadas. */
    public String authenticate() {
        Map<String, Object> body = webClient.post()
                .uri(baseUrl + "/auth")
                .bodyValue(Map.of("clientId", clientId, "clientSecret", clientSecret))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
        if (body == null || body.get("apiKey") == null) {
            throw new IllegalStateException("Pluggy não devolveu apiKey — confira PLUGGY_CLIENT_ID/SECRET");
        }
        return String.valueOf(body.get("apiKey"));
    }

    /** GET /accounts?itemId= — contas de uma conexão (item) do usuário. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> accounts(String apiKey, String itemId) {
        Map<String, Object> body = webClient.get()
                .uri(baseUrl + "/accounts?itemId={itemId}", itemId)
                .header("X-API-KEY", apiKey)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
        Object results = body != null ? body.get("results") : null;
        return results instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** GET /transactions — paginado; devolve todas as páginas da janela. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> transactions(String apiKey, String accountId, LocalDate from, LocalDate to) {
        List<Map<String, Object>> all = new ArrayList<>();
        int page = 1;
        int totalPages = 1;
        while (page <= totalPages) {
            Map<String, Object> body = webClient.get()
                    .uri(baseUrl + "/transactions?accountId={accountId}&from={from}&to={to}&pageSize={size}&page={page}",
                            accountId, from, to, PAGE_SIZE, page)
                    .header("X-API-KEY", apiKey)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .block();
            if (body == null) break;
            Object results = body.get("results");
            if (results instanceof List<?> list) {
                all.addAll((List<Map<String, Object>>) list);
            }
            Object total = body.get("totalPages");
            totalPages = total instanceof Number n ? n.intValue() : 1;
            page++;
        }
        return all;
    }
}
