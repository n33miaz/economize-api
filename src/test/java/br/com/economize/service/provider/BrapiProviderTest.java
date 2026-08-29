package br.com.economize.service.provider;

import br.com.economize.config.MarketCatalogProperties;
import br.com.economize.dto.Indicator;
import br.com.economize.service.catalog.QuoteBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({ "unchecked", "rawtypes" })
class BrapiProviderTest {

    private static final String BASE_URL = "https://example.test/brapi";

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private MarketSnapshotStore snapshotStore;
    private MarketCatalogProperties properties;
    private BrapiProvider provider;

    private final List<String> requestedUris = new ArrayList<>();
    private final AtomicBoolean failRequests = new AtomicBoolean(false);
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        snapshotStore = new MarketSnapshotStore();
        properties = new MarketCatalogProperties();
        provider = newProvider(new QuoteBudget(properties));

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenAnswer(inv -> {
            requestedUris.add(inv.getArgument(0));
            return requestHeadersSpec;
        });
        when(requestHeadersSpec.header(anyString(), any(String[].class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenAnswer(inv -> {
            if (failRequests.get()) {
                RuntimeException error = failure.get();
                return Mono.error(error != null ? error : new RuntimeException("brapi indisponivel"));
            }
            String uri = requestedUris.get(requestedUris.size() - 1);
            String ticker = uri.substring(uri.lastIndexOf('/') + 1);
            return Mono.just(Map.of("results", List.of(Map.of(
                    "symbol", ticker,
                    "shortName", "Nome " + ticker,
                    "regularMarketPrice", 10.5,
                    "regularMarketChangePercent", 1.2))));
        });
    }

    private BrapiProvider newProvider(QuoteBudget budget) {
        return new BrapiProvider(webClient, BASE_URL, "token-teste", snapshotStore, budget);
    }

    @Test
    @DisplayName("Deve fazer uma requisição por ticker, sem lote com vírgula")
    void shouldFetchOneRequestPerTicker() {
        StepVerifier.create(provider.fetchDefaultIndicators())
                .assertNext(indicators -> {
                    assertEquals(7, indicators.size());
                    assertEquals("PETR4", indicators.get(0).getCode());
                    assertEquals("^BVSP", indicators.get(6).getCode());
                    assertEquals("index", indicators.get(6).getType());
                })
                .verifyComplete();

        assertEquals(7, requestedUris.size());
        assertTrue(requestedUris.stream().noneMatch(uri -> uri.contains(",")),
                "lote com vírgula não pode ser enviado à Brapi");
        assertTrue(requestedUris.contains(BASE_URL + "/quote/^BVSP"));
        // token nunca vai na URL (vai no header Authorization)
        assertTrue(requestedUris.stream().noneMatch(uri -> uri.contains("token")));
    }

    @Test
    @DisplayName("Deve servir snapshot stale quando a Brapi falhar")
    void shouldServeStaleSnapshotOnFailure() {
        StepVerifier.create(provider.searchIndicator("PETR4"))
                .assertNext(indicators -> assertEquals(1, indicators.size()))
                .verifyComplete();

        failRequests.set(true);

        StepVerifier.create(provider.searchIndicator("PETR4"))
                .assertNext(indicators -> {
                    assertEquals(1, indicators.size());
                    assertEquals("PETR4", indicators.get(0).getCode());
                    assertTrue(indicators.get(0).isStale(),
                            "preço vindo do snapshot precisa se declarar stale, senão a UI o mostra como vivo");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Deve retornar lista vazia quando falhar e não houver snapshot stale")
    void shouldReturnEmptyOnFailureWithoutStale() {
        failRequests.set(true);

        StepVerifier.create(provider.searchIndicator("VALE3"))
                .assertNext(indicators -> assertTrue(indicators.isEmpty()))
                .verifyComplete();

        assertFalse(snapshotStore.find("brapi:VALE3").isPresent());
    }

    // ------------------------------------------------------------------ cota

    @Test
    @DisplayName("Orçamento diário cobre TAMBÉM o /all: esgotado, a Home cai para stale em vez de vazio")
    void exhaustedBudgetShouldDegradeHomeToStale() {
        // primeira recarga com orçamento normal: enche o snapshot
        StepVerifier.create(provider.fetchDefaultIndicators()).expectNextCount(1).verifyComplete();
        int firstRound = requestedUris.size();

        // dia seguinte de um usuário movimentado: a cota acabou
        provider = newProvider(new QuoteBudget(budgetOf(0, 0)));

        StepVerifier.create(provider.fetchDefaultIndicators())
                .assertNext(indicators -> {
                    assertEquals(7, indicators.size(), "Home nunca pode ficar vazia por causa da cota");
                    assertTrue(indicators.stream().allMatch(Indicator::isStale));
                })
                .verifyComplete();

        assertEquals(firstRound, requestedUris.size(), "sem orçamento não pode sair requisição nenhuma");
    }

    @Test
    @DisplayName("Busca do usuário consome o mesmo orçamento compartilhado")
    void searchShouldSpendTheSharedBudget() {
        QuoteBudget budget = new QuoteBudget(budgetOf(100, 2));
        provider = newProvider(budget);

        StepVerifier.create(provider.searchIndicator("AAA3,BBB3,CCC3")).expectNextCount(1).verifyComplete();

        assertEquals(2, requestedUris.size(), "o terceiro ticker não cabia no orçamento sob demanda");
        assertEquals(0, budget.remaining());
    }

    @Test
    @DisplayName("Ticker repetido na mesma busca não custa duas requisições")
    void duplicatedTickerShouldCostOneRequest() {
        StepVerifier.create(provider.searchIndicator("PETR4,petr4, PETR4 "))
                .assertNext(indicators -> assertEquals(1, indicators.size()))
                .verifyComplete();

        assertEquals(1, requestedUris.size());
    }

    // ------------------------------------------------------------- quarentena

    @Test
    @DisplayName("Ativo que a Brapi diz não existir (404) entra em quarentena e para de gastar cota")
    void notFoundShouldQuarantineTheTicker() {
        QuoteBudget budget = new QuoteBudget(budgetOf(100, 100));
        provider = newProvider(budget);
        failRequests.set(true);
        failure.set(notFound());

        StepVerifier.create(provider.searchIndicator("XPTO99"))
                .assertNext(indicators -> assertTrue(indicators.isEmpty()))
                .verifyComplete();
        assertEquals(1, requestedUris.size());
        assertEquals(99, budget.remaining());

        StepVerifier.create(provider.searchIndicator("XPTO99"))
                .assertNext(indicators -> assertTrue(indicators.isEmpty()))
                .verifyComplete();

        assertEquals(1, requestedUris.size(), "ticker inexistente não pode ser repedido dentro da quarentena");
        assertEquals(99, budget.remaining(), "e muito menos consumir orçamento de novo");
    }

    @Test
    @DisplayName("Falha transitória NÃO quarentena: o ativo volta a ser pedido na chamada seguinte")
    void transientFailureShouldNotQuarantine() {
        // 429/timeout/5xx acontecem o tempo todo; sumir com o ativo por 6h por
        // causa deles seria transformar soluço do provedor em ativo deslistado
        failRequests.set(true);
        failure.set(new RuntimeException("timeout"));

        StepVerifier.create(provider.searchIndicator("VALE3")).expectNextCount(1).verifyComplete();
        assertEquals(1, requestedUris.size());

        failRequests.set(false);
        failure.set(null);

        StepVerifier.create(provider.searchIndicator("VALE3"))
                .assertNext(indicators -> {
                    assertEquals(1, indicators.size());
                    assertFalse(indicators.get(0).isStale(), "agora é cotação viva de novo");
                })
                .verifyComplete();
        assertEquals(2, requestedUris.size());
    }

    @Test
    @DisplayName("Erro 5xx não quarentena mesmo repetido: continua degradando para stale")
    void serverErrorShouldKeepServingStale() {
        StepVerifier.create(provider.searchIndicator("ITUB4")).expectNextCount(1).verifyComplete();

        failRequests.set(true);
        failure.set(WebClientResponseException.create(503, "Service Unavailable",
                org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

        StepVerifier.create(provider.searchIndicator("ITUB4"))
                .assertNext(indicators -> assertTrue(indicators.get(0).isStale()))
                .verifyComplete();
        StepVerifier.create(provider.searchIndicator("ITUB4"))
                .assertNext(indicators -> assertTrue(indicators.get(0).isStale()))
                .verifyComplete();

        assertEquals(3, requestedUris.size(), "5xx é transitório: a pergunta continua sendo feita");
    }

    private WebClientResponseException notFound() {
        return WebClientResponseException.create(404, "Not Found",
                org.springframework.http.HttpHeaders.EMPTY, new byte[0], null);
    }

    private MarketCatalogProperties budgetOf(int total, int onDemand) {
        MarketCatalogProperties custom = new MarketCatalogProperties();
        custom.setDailyProviderBudget(total);
        custom.setDailyQuoteBudget(onDemand);
        return custom;
    }
}
