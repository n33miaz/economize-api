package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.model.BankTransaction;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.BankStatementService;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.mockito.Mockito.when;

@WebFluxTest(BankStatementController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class })
class BankStatementControllerTest {

    private static final String EMAIL = "teste@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private BankStatementService bankStatementService;

    @Test
    @DisplayName("GET /bank-statements - o histórico volta como UM corpo JSON, na ordem do serviço")
    void listReturnsOneJsonBody() {
        // Medido em campo: 1.688 linhas em Flux levavam 18 s (uma descarga por
        // elemento) contra 1,3 s em lista. O contrato para o app é o mesmo —
        // um array — e é isso que o teste trava, junto com a ordem
        when(bankStatementService.listTransactions(EMAIL)).thenReturn(sample(3));

        webTestClient.get().uri("/api/v1/bank-statements")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.length()").isEqualTo(3)
                .jsonPath("$[0].description").isEqualTo("Lançamento 0")
                .jsonPath("$[2].description").isEqualTo("Lançamento 2")
                .jsonPath("$[0].amount").isEqualTo(-10.0);
    }

    @Test
    @DisplayName("GET /bank-statements - histórico vazio é um array vazio, não 204")
    void emptyHistoryIsAnEmptyArray() {
        when(bankStatementService.listTransactions(EMAIL)).thenReturn(List.of());

        webTestClient.get().uri("/api/v1/bank-statements")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("[]");
    }

    @Test
    @DisplayName("GET /bank-statements - sem token é 401")
    void requiresAuthentication() {
        webTestClient.get().uri("/api/v1/bank-statements")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private static List<BankTransaction> sample(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> BankTransaction.builder()
                        .id(UUID.randomUUID())
                        .transactionId("T" + i)
                        .type("DEBIT")
                        .amount(BigDecimal.valueOf(-10L * (i + 1)))
                        .description("Lançamento " + i)
                        .date(OffsetDateTime.of(2026, 8, 1 + i, 0, 0, 0, 0, ZoneOffset.UTC))
                        .internalTransfer(false)
                        .build())
                .toList();
    }

    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }
}
