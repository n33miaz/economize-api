package br.com.economize.service.provider;

import br.com.economize.dto.Indicator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({ "unchecked", "rawtypes" })
class AwesomeApiProviderTest {

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private MarketSnapshotStore snapshotStore;
    private AwesomeApiProvider provider;

    @BeforeEach
    void setUp() {
        snapshotStore = new MarketSnapshotStore();
        provider = new AwesomeApiProvider(webClient, "https://example.test/awesome", snapshotStore);

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
    }

    private Map<String, Indicator> sampleResponse() {
        Indicator usd = new Indicator();
        usd.setCode("USD");
        usd.setName("Dólar Americano");
        usd.setBuy(new BigDecimal("5.40"));
        return Map.of("USD", usd);
    }

    @Test
    @DisplayName("Deve buscar moedas e guardar snapshot para uso stale")
    void shouldFetchAndStoreSnapshot() {
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(sampleResponse()));

        StepVerifier.create(provider.fetchDefaultIndicators())
                .assertNext(indicators -> {
                    assertEquals(1, indicators.size());
                    assertEquals("currency_USD", indicators.get(0).getId());
                })
                .verifyComplete();

        assertTrue(snapshotStore.find("awesome:all").isPresent());
    }

    @Test
    @DisplayName("Deve servir snapshot stale quando a AwesomeAPI falhar (ex.: 429)")
    void shouldServeStaleSnapshotOnFailure() {
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(sampleResponse()))
                .thenReturn(Mono.error(new RuntimeException("429 QuotaExceeded")));

        StepVerifier.create(provider.fetchDefaultIndicators())
                .assertNext(indicators -> assertEquals(1, indicators.size()))
                .verifyComplete();

        StepVerifier.create(provider.fetchDefaultIndicators())
                .assertNext(indicators -> {
                    assertEquals(1, indicators.size());
                    assertEquals("USD", indicators.get(0).getCode());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Deve retornar lista vazia quando falhar sem snapshot stale")
    void shouldReturnEmptyOnFailureWithoutStale() {
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.error(new RuntimeException("429 QuotaExceeded")));

        StepVerifier.create(provider.fetchDefaultIndicators())
                .assertNext(indicators -> assertTrue(indicators.isEmpty()))
                .verifyComplete();
    }
}
