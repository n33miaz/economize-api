package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.config.MarketCatalogProperties;
import br.com.economize.dto.Indicator;
import br.com.economize.service.IndicatorService;
import br.com.economize.service.catalog.MarketCatalogService;
import br.com.economize.service.provider.BrapiProvider;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(IndicatorController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class, MarketCatalogProperties.class })
class IndicatorControllerTest {

        @Autowired
        private WebTestClient webTestClient;

        @Autowired
        private JwtUtil jwtUtil;

        @MockitoBean
        private IndicatorService indicatorService;

        @MockitoBean
        private MarketCatalogService catalogService;

        // EC-103: o detalhe do papel fala direto com o provedor, porque é nele
        // que vive a cota diária. A fatia monta o controller de verdade, então
        // a dependência precisa existir mesmo quando o teste não a exercita
        @MockitoBean
        private BrapiProvider brapiProvider;

        @Test
        @DisplayName("GET /all - Deve retornar lista de indicadores com sucesso")
        void shouldReturnAllIndicators() {
                Indicator ind = new Indicator();
                ind.setCode("USD");
                ind.setName("Dólar");
                ind.setBuy(new BigDecimal("5.00"));

                when(indicatorService.getAllIndicators()).thenReturn(Mono.just(List.of(ind)));

                webTestClient.get()
                                .uri("/api/v1/indicators/all")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .accept(MediaType.APPLICATION_JSON)
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$[0].code").isEqualTo("USD")
                                .jsonPath("$[0].buy").isEqualTo(5.00)
                                // a marca interna de procedência do preço não
                                // pode vazar para o contrato do APK publicado
                                .jsonPath("$[0].stale").doesNotExist();
        }

        @Test
        @DisplayName("GET /search - Ticker único (uso do APK publicado) continua igual")
        void shouldSearchSingleTicker() {
                Indicator ind = new Indicator();
                ind.setCode("PETR4");
                when(indicatorService.searchIndicators("PETR4")).thenReturn(Mono.just(List.of(ind)));

                webTestClient.get()
                                .uri("/api/v1/indicators/search?query=PETR4")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$[0].code").isEqualTo("PETR4");
        }

        @Test
        @DisplayName("GET /search - Termo é normalizado antes de virar requisição ao provedor")
        void shouldNormalizeSearchTerm() {
                when(indicatorService.searchIndicators("PETR4,VALE3")).thenReturn(Mono.just(List.of()));

                // caixa, espaço e repetição não podem virar cotação paga duas vezes
                webTestClient.get()
                                .uri("/api/v1/indicators/search?query=petr4, vale3 ,PETR4")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isOk();

                verify(indicatorService).searchIndicators("PETR4,VALE3");
        }

        @Test
        @DisplayName("GET /search - Acima do teto de tickers responde 400 em ProblemDetail")
        void shouldRejectSearchAboveTickerCap() {
                // uma chamada dessas custaria 11 requisições da cota diária da Brapi
                webTestClient.get()
                                .uri("/api/v1/indicators/search?query="
                                                + "AAA3,BBB3,CCC3,DDD3,EEE3,FFF3,GGG3,HHH3,III3,JJJ3,KKK3")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isBadRequest()
                                .expectBody()
                                .jsonPath("$.title").isEqualTo("Requisição Inválida")
                                .jsonPath("$.status").isEqualTo(400)
                                .jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("10 ativos"));

                verify(indicatorService, never()).searchIndicators(anyString());
        }

        @Test
        @DisplayName("GET /search - Busca vazia é erro do cliente, não chamada ao provedor")
        void shouldRejectEmptySearch() {
                webTestClient.get()
                                .uri("/api/v1/indicators/search?query=,,")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isBadRequest();

                verify(indicatorService, never()).searchIndicators(anyString());
        }

        @Test
        @DisplayName("GET /search - Código fora do formato da B3 é 400 e não chega ao provedor")
        void shouldRejectMalformedTicker() {
                // o código ia cru para a URL da Brapi e para a chave do snapshot
                for (String query : List.of("petr4!x", "PETR4/../X", "ABCDEFGHIJKLM", "PETR4,VALE3 X")) {
                        webTestClient.get()
                                        .uri(builder -> builder.path("/api/v1/indicators/search")
                                                        .queryParam("query", "{q}").build(query))
                                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                        .exchange()
                                        .expectStatus().isBadRequest();
                }
                verify(indicatorService, never()).searchIndicators(anyString());
        }

        @Test
        @DisplayName("GET /search - Índice com ^, FII e BDR passam na validação")
        void shouldAcceptRealTickerShapes() {
                when(indicatorService.searchIndicators("^BVSP,MXRF11,AAPL34")).thenReturn(Mono.just(List.of()));

                webTestClient.get()
                                .uri(builder -> builder.path("/api/v1/indicators/search")
                                                .queryParam("query", "{q}").build("^bvsp,MXRF11,AAPL34"))
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isOk();

                verify(indicatorService).searchIndicators("^BVSP,MXRF11,AAPL34");
        }

        @Test
        @DisplayName("GET /{code}/detail - Código fora do formato é 400; válido chega normalizado")
        void detailShouldValidateTicker() {
                webTestClient.get()
                                .uri("/api/v1/indicators/{code}/detail", "PETR4!X")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isBadRequest();
                verify(brapiProvider, never()).fetchDetail(anyString());

                when(brapiProvider.fetchDetail("PETR4")).thenReturn(Mono.empty());
                webTestClient.get()
                                .uri("/api/v1/indicators/{code}/detail", "petr4")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isNotFound();
                verify(brapiProvider).fetchDetail("PETR4");
        }

        @Test
        @DisplayName("GET /historical - Janela fora da lista e código fora do catálogo são 400, sem gastar cota")
        void historicalShouldRejectUnknownWindowAndCode() {
                for (String uri : List.of("/api/v1/indicators/historical/USD?days=8",
                                "/api/v1/indicators/historical/USD?days=120",
                                "/api/v1/indicators/historical/XYZ?days=7",
                                "/api/v1/indicators/historical/USDBRL?days=7",
                                "/api/v1/indicators/historical/US1?days=7")) {
                        webTestClient.get()
                                        .uri(uri)
                                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                        .exchange()
                                        .expectStatus().isBadRequest();
                }
                verify(indicatorService, never()).getHistoricalData(anyString(), org.mockito.ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("GET /historical - Chamada do app (padrão 7 dias, id com prefixo) chega normalizada")
        void historicalShouldAcceptAppCall() {
                when(indicatorService.getHistoricalData("USD", 7)).thenReturn(Mono.just(List.of()));
                when(indicatorService.getHistoricalData("BTC", 7)).thenReturn(Mono.just(List.of()));

                webTestClient.get().uri("/api/v1/indicators/historical/USD")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange().expectStatus().isOk();
                webTestClient.get().uri("/api/v1/indicators/historical/crypto_btc?days=7")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange().expectStatus().isOk();

                verify(indicatorService).getHistoricalData("USD", 7);
                verify(indicatorService).getHistoricalData("BTC", 7);
        }

        @Test
        @DisplayName("GET /convert - Deve realizar conversão corretamente")
        void shouldConvertCurrency() {
                BigDecimal resultValue = new BigDecimal("20.00");

                when(indicatorService.calculateConversion(eq("USD"), any(BigDecimal.class)))
                                .thenReturn(Mono.just(resultValue));

                webTestClient.get()
                                .uri(uriBuilder -> uriBuilder
                                                .path("/api/v1/indicators/convert")
                                                .queryParam("code", "USD")
                                                .queryParam("amount", "100.00")
                                                .build())
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.result").isEqualTo(20.00)
                                .jsonPath("$.currency").isEqualTo("USD");
        }

        @Test
        @DisplayName("GET /convert - Deve retornar 400 Bad Request para moeda inválida")
        void shouldReturnBadRequestForInvalidCurrency() {
                when(indicatorService.calculateConversion(any(), any()))
                                .thenReturn(Mono.error(new IllegalArgumentException("Moeda inválida")));

                webTestClient.get()
                                .uri(uriBuilder -> uriBuilder
                                                .path("/api/v1/indicators/convert")
                                                .queryParam("code", "INVALID")
                                                .queryParam("amount", "100")
                                                .build())
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isBadRequest()
                                .expectBody()
                                .jsonPath("$.error").exists();
        }

        private String bearerToken() {
                return "Bearer " + jwtUtil.generateToken("teste@economize.app");
        }
}
