package br.com.economize.service.connector.pluggy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Paginação do cliente do Pluggy. Ler só a primeira página é uma aposta
 * silenciosa: quem tem várias contas no mesmo banco perde contas inteiras do
 * sync — e junto com elas o cartão que autoriza reconhecer o pagamento de
 * fatura.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({ "unchecked", "rawtypes" })
class PluggyClientTest {

    private static final String BASE_URL = "https://api.pluggy.test";

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private PluggyClient client;

    private final List<String> requestedUris = new ArrayList<>();
    private final Deque<Map<String, Object>> bodies = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        client = new PluggyClient(webClient, BASE_URL, "client-id", "client-secret");

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(URI.class))).thenAnswer(invocation -> {
            requestedUris.add(invocation.getArgument(0).toString());
            return requestHeadersSpec;
        });
        when(requestHeadersSpec.header(anyString(), any(String[].class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                .thenAnswer(invocation -> Mono.justOrEmpty(bodies.poll()));
    }

    @Test
    @DisplayName("accounts percorre todas as páginas e devolve as contas de todas elas")
    void accountsShouldFollowEveryPage() {
        bodies.add(page(List.of(account("acc-1"), account("acc-2")), "?itemId=item-1&after=cursor-2"));
        bodies.add(page(List.of(account("acc-3")), null));

        List<Map<String, Object>> accounts = client.accounts("api-key", "item-1");

        assertThat(accounts).extracting(a -> a.get("id")).containsExactly("acc-1", "acc-2", "acc-3");
        assertThat(requestedUris).containsExactly(
                BASE_URL + "/accounts?itemId=item-1",
                BASE_URL + "/accounts?itemId=item-1&after=cursor-2");
    }

    @Test
    @DisplayName("cursor devolvido como URL completa não pode virar URL concatenada e quebrada")
    void nextAsAbsoluteUrlShouldBeReducedToItsQuery() {
        bodies.add(page(List.of(transaction("t-1")),
                BASE_URL + "/v2/transactions?accountId=acc-1&after=cursor-2"));
        bodies.add(page(List.of(transaction("t-2")), ""));

        List<Map<String, Object>> transactions = client.transactions("api-key", "acc-1",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(transactions).extracting(t -> t.get("id")).containsExactly("t-1", "t-2");
        // sem tratar a URL absoluta, a segunda página viraria
        // ".../v2/transactionshttps://api.pluggy.test/..." e o extrato seria
        // truncado em silêncio
        assertThat(requestedUris.get(1))
                .isEqualTo(BASE_URL + "/v2/transactions?accountId=acc-1&after=cursor-2");
    }

    @Test
    @DisplayName("cursor em formato irreconhecível encerra a leitura em vez de repetir a página")
    void unknownCursorShouldStopInsteadOfLooping() {
        bodies.add(page(List.of(account("acc-1")), "cursor-sem-query"));

        List<Map<String, Object>> accounts = client.accounts("api-key", "item-1");

        assertThat(accounts).hasSize(1);
        assertThat(requestedUris).hasSize(1);
    }

    @Test
    @DisplayName("cursor que se repete para sempre esbarra na trava de páginas")
    void repeatingCursorShouldHitThePageGuard() {
        // dado de terceiro: um cursor que nunca termina deixaria o sync girando
        // até a instância cair
        for (int i = 0; i < 500; i++) {
            bodies.add(page(List.of(account("acc-" + i)), "?itemId=item-1&after=sempre-o-mesmo"));
        }

        List<Map<String, Object>> accounts = client.accounts("api-key", "item-1");

        assertThat(requestedUris).hasSize(200);
        assertThat(accounts).hasSize(200);
    }

    @Test
    @DisplayName("resposta sem 'next' encerra a paginação")
    void missingNextShouldEndPagination() {
        Map<String, Object> body = new HashMap<>();
        body.put("results", List.of(account("acc-1")));
        bodies.add(body);

        assertThat(client.accounts("api-key", "item-1")).hasSize(1);
        assertThat(requestedUris).hasSize(1);
    }

    private Map<String, Object> page(List<Map<String, Object>> results, String next) {
        Map<String, Object> body = new HashMap<>();
        body.put("results", results);
        body.put("next", next);
        return body;
    }

    private Map<String, Object> account(String id) {
        return Map.of("id", id, "type", "BANK");
    }

    private Map<String, Object> transaction(String id) {
        return Map.of("id", id, "amount", "-10.00", "date", "2026-08-10");
    }
}
