package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.config.MarketCatalogProperties;
import br.com.economize.dto.Indicator;
import br.com.economize.dto.catalog.CatalogItem;
import br.com.economize.dto.catalog.CatalogPage;
import br.com.economize.dto.catalog.CatalogPageInfo;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.IndicatorService;
import br.com.economize.service.catalog.MarketCatalogService;
import br.com.economize.service.provider.BrapiProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebFluxTest(IndicatorController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class, MarketCatalogProperties.class })
class IndicatorCatalogControllerTest {

        @Autowired
        private WebTestClient webTestClient;

        @Autowired
        private JwtUtil jwtUtil;

        @MockitoBean
        private IndicatorService indicatorService;

        @MockitoBean
        private MarketCatalogService catalogService;

        // Mesma razão do IndicatorControllerTest: o controller agora depende do
        // provedor de ações (EC-103) e a fatia precisa dele no contexto
        @MockitoBean
        private BrapiProvider brapiProvider;

        @Test
        @DisplayName("GET /catalog - Deve devolver itens e metadados de paginação")
        void shouldReturnCatalogPage() {
                when(catalogService.page(any())).thenReturn(Mono.just(samplePage()));

                webTestClient.get()
                                .uri("/api/v1/indicators/catalog?limit=1")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.items[0].id").isEqualTo("stock_PETR4")
                                .jsonPath("$.items[0].code").isEqualTo("PETR4")
                                .jsonPath("$.items[0].segment").isEqualTo("acoes")
                                .jsonPath("$.items[0].quoteStatus").isEqualTo("LIVE")
                                .jsonPath("$.page.hasMore").isEqualTo(true)
                                .jsonPath("$.page.nextCursor").isEqualTo("cursor-fake")
                                .jsonPath("$.page.totalMatched").isEqualTo(180)
                                .jsonPath("$.page.catalogVersion").exists();
        }

        @Test
        @DisplayName("GET /catalog - Item sem cotação mantém identidade e marca UNQUOTED")
        void shouldExposeUnquotedItems() {
                CatalogPageInfo info = new CatalogPageInfo();
                info.setLimit(1);
                info.setReturned(1);
                info.setHasMore(false);
                when(catalogService.page(any())).thenReturn(Mono.just(CatalogPage.of(
                                List.of(CatalogItem.withoutQuote("stock_XPTO3", "stock", "XPTO3", "XPTO ON",
                                                "acoes")),
                                info)));

                webTestClient.get()
                                .uri("/api/v1/indicators/catalog")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.items[0].quoteStatus").isEqualTo("UNQUOTED")
                                .jsonPath("$.items[0].name").isEqualTo("XPTO ON")
                                .jsonPath("$.items[0].buy").isEmpty()
                                .jsonPath("$.page.hasMore").isEqualTo(false)
                                .jsonPath("$.page.nextCursor").isEmpty();
        }

        @Test
        @DisplayName("GET /catalog - Cursor inválido deve responder 400 em ProblemDetail")
        void shouldReturnProblemDetailForInvalidCursor() {
                when(catalogService.page(any()))
                                .thenReturn(Mono.error(new IllegalArgumentException("Cursor inválido: formato não reconhecido.")));

                webTestClient.get()
                                .uri("/api/v1/indicators/catalog?cursor=quebrado")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isBadRequest()
                                .expectBody()
                                .jsonPath("$.title").isEqualTo("Requisição Inválida")
                                .jsonPath("$.status").isEqualTo(400)
                                .jsonPath("$.detail").isEqualTo("Cursor inválido: formato não reconhecido.");
        }

        @Test
        @DisplayName("GET /catalog - Limite não positivo deve responder 400 em ProblemDetail")
        void shouldReturnProblemDetailForNonPositiveLimit() {
                webTestClient.get()
                                .uri("/api/v1/indicators/catalog?limit=0")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isBadRequest()
                                .expectBody()
                                .jsonPath("$.title").isEqualTo("Requisição Inválida")
                                .jsonPath("$.detail").isEqualTo("O parâmetro limit deve ser maior que zero.");
        }

        @Test
        @DisplayName("GET /catalog - Tipo desconhecido deve responder 400 em ProblemDetail")
        void shouldReturnProblemDetailForUnknownType() {
                webTestClient.get()
                                .uri("/api/v1/indicators/catalog?type=acao")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isBadRequest()
                                .expectBody()
                                .jsonPath("$.title").isEqualTo("Requisição Inválida");
        }

        @Test
        @DisplayName("GET /all - Sem limit nem offset o contrato antigo continua igual")
        void legacyRouteShouldStayIntactWithoutParameters() {
                when(indicatorService.getAllIndicators())
                                .thenReturn(Mono.just(List.of(indicator("USD", "Dólar"),
                                                indicator("EUR", "Euro"))));

                webTestClient.get()
                                .uri("/api/v1/indicators/all")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.length()").isEqualTo(2)
                                .jsonPath("$[0].code").isEqualTo("USD");
        }

        @Test
        @DisplayName("GET /all - limit e offset fatiam a mesma lista, sem mudar o shape")
        void legacyRouteShouldSliceWhenAsked() {
                when(indicatorService.getAllIndicators())
                                .thenReturn(Mono.just(List.of(indicator("USD", "Dólar"), indicator("EUR", "Euro"),
                                                indicator("GBP", "Libra"))));

                webTestClient.get()
                                .uri("/api/v1/indicators/all?limit=1&offset=1")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.length()").isEqualTo(1)
                                .jsonPath("$[0].code").isEqualTo("EUR");
        }

        @Test
        @DisplayName("GET /all - offset além do fim devolve array vazio, não erro")
        void legacyRouteShouldReturnEmptyArrayPastTheEnd() {
                when(indicatorService.getAllIndicators())
                                .thenReturn(Mono.just(List.of(indicator("USD", "Dólar"))));

                webTestClient.get()
                                .uri("/api/v1/indicators/all?limit=5&offset=50")
                                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.length()").isEqualTo(0);
        }

        private CatalogPage samplePage() {
                Indicator quote = indicator("PETR4", "Petrobras PN");
                quote.setId("stock_PETR4");
                quote.setType("stock");

                CatalogPageInfo info = new CatalogPageInfo();
                info.setLimit(1);
                info.setReturned(1);
                info.setHasMore(true);
                info.setNextCursor("cursor-fake");
                info.setTotalMatched(180);
                info.setCatalogVersion("2026.08.1");
                info.setRankEpoch(123L);
                info.setQuoteBudgetRemaining(699);

                return CatalogPage.of(
                                List.of(CatalogItem.fromQuote(quote, "acoes", CatalogItem.QUOTE_LIVE)), info);
        }

        private Indicator indicator(String code, String name) {
                Indicator indicator = new Indicator();
                indicator.setId("currency_" + code);
                indicator.setType("currency");
                indicator.setCode(code);
                indicator.setName(name);
                indicator.setBuy(new BigDecimal("5.00"));
                return indicator;
        }

        private String bearerToken() {
                return "Bearer " + jwtUtil.generateToken("teste@economize.app");
        }
}
