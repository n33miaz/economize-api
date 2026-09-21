package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.shopping.ShoppingResponses;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.shopping.ShoppingTripService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * As rotas do carrinho de compras (V39): contrato de status HTTP e formato do
 * corpo — o serviço é dublado, a regra dele (fusão, conciliação, histórico)
 * tem teste próprio em {@code ShoppingTripServiceTest}.
 *
 * <p>Sem 403 de propósito: recurso que não é do dono nem da família dele
 * responde 404 em qualquer rota (regra do EC-037, documentada no serviço) —
 * nunca 403, que confirmaria que o id existe.
 */
@WebFluxTest(ShoppingController.class)
@Import({CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class})
@DisplayName("ShoppingController — carrinho de compras (V39)")
class ShoppingControllerTest {

    private static final String EMAIL = "dono@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private ShoppingTripService service;

    private String bearer() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }

    private ShoppingResponses.TripItem trip(String clientId, String status) {
        return new ShoppingResponses.TripItem(
                UUID.randomUUID(), clientId, UUID.randomUUID(), "Ana", null, false,
                "Mercado da Esquina", status, null,
                OffsetDateTime.parse("2026-09-21T09:00:00Z"), null, null,
                // sem nota fiscal lida: é o estado de toda compra antes do caixa
                null, null,
                null, null,
                new BigDecimal("58.40"), 2, List.of(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    // ------------------------------------------------------------ GET /trips

    @Test
    @DisplayName("GET /trips devolve a lista com o total calculado no servidor")
    void listaCompras() {
        when(service.list(EMAIL, null, null)).thenReturn(List.of(trip("c1", "OPEN")));

        webTestClient.get().uri("/api/v1/shopping/trips")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].clientId").isEqualTo("c1")
                .jsonPath("$[0].status").isEqualTo("OPEN")
                .jsonPath("$[0].total").isEqualTo(58.40)
                .jsonPath("$[0].ownerName").isEqualTo("Ana");
    }

    @Test
    @DisplayName("GET /trips repassa status e limit ao serviço")
    void listaComFiltro() {
        when(service.list(EMAIL, "CLOSED", 10)).thenReturn(List.of());

        webTestClient.get().uri("/api/v1/shopping/trips?status=CLOSED&limit=10")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk();

        verify(service).list(EMAIL, "CLOSED", 10);
    }

    @Test
    @DisplayName("Sem token nenhuma rota do carrinho responde")
    void semTokenNaoResponde() {
        webTestClient.get().uri("/api/v1/shopping/trips")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get().uri("/api/v1/shopping/summary")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(service, never()).list(any(), any(), any());
    }

    // ------------------------------------------------------------ PUT /trips/{clientId}

    @Test
    @DisplayName("PUT /trips/{clientId} funde e devolve a viagem consolidada")
    void enviaCompra() {
        when(service.upsert(eq(EMAIL), eq("c1"), any())).thenReturn(trip("c1", "OPEN"));

        webTestClient.put().uri("/api/v1/shopping/trips/c1")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "storeName", "Mercado da Esquina",
                        "shareWithFamily", true,
                        "items", List.of(Map.of(
                                "clientId", "i1",
                                "name", "Arroz",
                                "quantity", 2,
                                "unitPrice", 25.90,
                                "checked", true,
                                "clientUpdatedAt", "2026-09-21T09:05:00Z"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.clientId").isEqualTo("c1")
                .jsonPath("$.total").isEqualTo(58.40);

        verify(service).upsert(eq(EMAIL), eq("c1"), any());
    }

    @Test
    @DisplayName("Item sem clientId (ou sem nome) é 400, e o serviço nem é chamado")
    void itemSemClientIdE400() {
        webTestClient.put().uri("/api/v1/shopping/trips/c1")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("items", List.of(Map.of("name", "Arroz"))))
                .exchange()
                .expectStatus().isBadRequest();

        verify(service, never()).upsert(any(), any(), any());
    }

    @Test
    @DisplayName("Quantidade zero ou negativa é 400 — balança não pesa isso")
    void quantidadeInvalidaE400() {
        webTestClient.put().uri("/api/v1/shopping/trips/c1")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("items", List.of(Map.of(
                        "clientId", "i1", "name", "Arroz", "quantity", 0))))
                .exchange()
                .expectStatus().isBadRequest();

        verify(service, never()).upsert(any(), any(), any());
    }

    @Test
    @DisplayName("Nome do mercado longo demais é 400 — é o tamanho da coluna")
    void nomeDoMercadoLongoDemaisE400() {
        webTestClient.put().uri("/api/v1/shopping/trips/c1")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("storeName", "a".repeat(121)))
                .exchange()
                .expectStatus().isBadRequest();

        verify(service, never()).upsert(any(), any(), any());
    }

    @Test
    @DisplayName("Identificador divergente entre corpo e rota é 400")
    void identificadorDivergenteE400() {
        when(service.upsert(eq(EMAIL), eq("c1"), any()))
                .thenThrow(new IllegalArgumentException("O identificador do corpo não é o da rota"));

        webTestClient.put().uri("/api/v1/shopping/trips/c1")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("clientId", "outro", "items", List.of()))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("O identificador do corpo não é o da rota");
    }

    // ------------------------------------------------------------ close / reconcile

    @Test
    @DisplayName("POST /trips/{clientId}/close funciona com ou sem corpo")
    void fechaCompra() {
        // any() do Mockito casa com nulo também — cobre o corpo omitido e o informado
        when(service.close(eq(EMAIL), eq("c1"), any())).thenReturn(trip("c1", "CLOSED"));

        webTestClient.post().uri("/api/v1/shopping/trips/c1/close")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("receiptTotal", 60.00))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("CLOSED");

        webTestClient.post().uri("/api/v1/shopping/trips/c1/close")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("POST /trips/{clientId}/close já conciliada é 400")
    void fecharJaConciliadaE400() {
        when(service.close(eq(EMAIL), eq("c1"), any()))
                .thenThrow(new IllegalArgumentException("Esta compra já foi conciliada com o extrato"));

        webTestClient.post().uri("/api/v1/shopping/trips/c1/close")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("POST /trips/{clientId}/close de compra alheia é 404")
    void fecharCompraAlheiaE404() {
        when(service.close(eq(EMAIL), eq("alheia"), any()))
                .thenThrow(new ResourceNotFoundException("Compra não encontrada"));

        webTestClient.post().uri("/api/v1/shopping/trips/alheia/close")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://economize.app/erros/nao-encontrado");
    }

    @Test
    @DisplayName("POST /trips/{clientId}/reconcile sem transactionId é 400")
    void reconciliarSemTransacaoE400() {
        webTestClient.post().uri("/api/v1/shopping/trips/c1/reconcile")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isBadRequest();

        verify(service, never()).reconcile(any(), any(), any());
    }

    @Test
    @DisplayName("POST /trips/{clientId}/reconcile com lançamento alheio é 404")
    void reconciliarComLancamentoAlheioE404() {
        UUID txId = UUID.randomUUID();
        when(service.reconcile(eq(EMAIL), eq("c1"), any()))
                .thenThrow(new ResourceNotFoundException("Lançamento não encontrado"));

        webTestClient.post().uri("/api/v1/shopping/trips/c1/reconcile")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("transactionId", txId.toString()))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("POST /trips/{clientId}/reconcile com sucesso muda o status")
    void reconciliarComSucesso() {
        UUID txId = UUID.randomUUID();
        when(service.reconcile(eq(EMAIL), eq("c1"), any())).thenReturn(trip("c1", "RECONCILED"));

        webTestClient.post().uri("/api/v1/shopping/trips/c1/reconcile")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("transactionId", txId.toString()))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("RECONCILED");
    }

    // ------------------------------------------------------------ candidatos

    @Test
    @DisplayName("GET reconcile-candidates devolve o id que a conciliação espera de volta")
    void candidatosDeConciliacao() {
        UUID txId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(service.reconcileCandidates(EMAIL, "c1")).thenReturn(List.of(
                new ShoppingResponses.ReconcileCandidate(
                        txId, OffsetDateTime.parse("2026-09-21T10:00:00Z"), "Mercado da Esquina",
                        new BigDecimal("58.40"), accountId, BigDecimal.ZERO)));

        webTestClient.get().uri("/api/v1/shopping/trips/c1/reconcile-candidates")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo(txId.toString())
                .jsonPath("$[0].accountId").isEqualTo(accountId.toString())
                .jsonPath("$[0].amount").isEqualTo(58.40);
    }

    // ------------------------------------------------------------ price-history

    @Test
    @DisplayName("GET /price-history sem o nome é 400")
    void historicoSemNomeE400() {
        webTestClient.get().uri("/api/v1/shopping/price-history")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("GET /price-history devolve 'history' — o nome do campo é o do contrato")
    void historicoDePrecos() {
        when(service.priceHistory(eq(EMAIL), eq("Arroz"), eq(null), eq(null))).thenReturn(
                new ShoppingResponses.PriceHistory("Arroz", "arroz",
                        new ShoppingResponses.PriceSummary(
                                new BigDecimal("25.90"), "Mercado da Esquina",
                                OffsetDateTime.parse("2026-09-21T09:00:00Z"),
                                new BigDecimal("24.00"), new BigDecimal("25.90"),
                                new BigDecimal("24.95"), 2),
                        List.of(new ShoppingResponses.PriceOccurrence(
                                "c1", "Mercado da Esquina", new BigDecimal("25.90"),
                                BigDecimal.ONE, null, true,
                                OffsetDateTime.parse("2026-09-21T09:00:00Z")))));

        webTestClient.get().uri("/api/v1/shopping/price-history?name=Arroz")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.history[0].storeName").isEqualTo("Mercado da Esquina")
                .jsonPath("$.history[0].tripClientId").isEqualTo("c1")
                .jsonPath("$.summary.lastPrice").isEqualTo(25.90)
                .jsonPath("$.summary.occurrences").isEqualTo(2);
    }

    // ------------------------------------------------------------ summary

    @Test
    @DisplayName("GET /summary devolve o resumo da Home")
    void resumoDaHome() {
        when(service.summary(EMAIL)).thenReturn(new ShoppingResponses.ShoppingSummary(
                2, new ShoppingResponses.LastTrip("c1", "Mercado da Esquina",
                        new BigDecimal("58.40"), OffsetDateTime.parse("2026-09-21T09:00:00Z")),
                new BigDecimal("120.90")));

        webTestClient.get().uri("/api/v1/shopping/summary")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openTrips").isEqualTo(2)
                .jsonPath("$.lastTrip.clientId").isEqualTo("c1")
                .jsonPath("$.monthTotal").isEqualTo(120.90);
    }

    @Test
    @DisplayName("GET /summary sem nenhuma compra ainda: lastTrip nulo")
    void resumoSemCompras() {
        when(service.summary(EMAIL)).thenReturn(new ShoppingResponses.ShoppingSummary(
                0, null, BigDecimal.ZERO));

        webTestClient.get().uri("/api/v1/shopping/summary")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.lastTrip").isEqualTo(null);
    }
}
