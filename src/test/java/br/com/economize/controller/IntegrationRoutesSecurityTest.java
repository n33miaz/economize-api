package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.analytics.IncomePatternResponse;
import br.com.economize.dto.integration.IntegrationKeyResponses;
import br.com.economize.dto.integration.IntegrationShoppingResponses;
import br.com.economize.security.IntegrationSecurityConfig;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.integration.IntegrationAdviceService;
import br.com.economize.service.integration.IntegrationKeyService;
import br.com.economize.service.shopping.ShoppingHistoryExportService;
import br.com.economize.service.shopping.ShoppingTripService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * As duas cadeias de segurança lado a lado (V44): a do app e a das rotas de
 * integração, com os serviços dublados.
 *
 * <p>O que esta suíte trava é a FRONTEIRA entre as duas credenciais, nos dois
 * sentidos: a chave não vale fora de {@code /api/v1/integration/**} (nem para
 * ler o carrinho, nem para gerir chaves), e o JWT de sessão não vale dentro.
 * E trava que toda recusa é igual — a resposta não conta se a chave testada
 * era malformada, desconhecida ou revogada.
 */
@WebFluxTest({IntegrationKeyController.class, IntegrationShoppingController.class, ShoppingController.class})
@Import({CorsConfig.class, SecurityConfig.class, IntegrationSecurityConfig.class,
        JwtUtil.class, JwtAuthenticationFilter.class})
@DisplayName("Rotas de integração — fronteira entre chave e JWT (V44)")
class IntegrationRoutesSecurityTest {

    private static final String DONO = "dono@economize.app";
    private static final String CHAVE_DO_DONO = "eco_" + "A".repeat(43);
    private static final String CHAVE_REVOGADA = "eco_" + "R".repeat(43);
    private static final String ADVICE = "/api/v1/integration/shopping/advice";
    private static final String TRIPS = "/api/v1/integration/shopping/trips";

    @Autowired
    private WebTestClient client;
    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private IntegrationKeyService keyService;
    @MockitoBean
    private IntegrationAdviceService adviceService;
    @MockitoBean
    private ShoppingHistoryExportService historyService;
    @MockitoBean
    private ShoppingTripService cartService;

    @BeforeEach
    void setUp() {
        // Só a chave do dono autentica; a revogada e qualquer outra voltam
        // vazio, que é o que o serviço real devolve para elas
        when(keyService.authenticate(CHAVE_DO_DONO)).thenReturn(Optional.of(DONO));
    }

    private String jwt() {
        return "Bearer " + jwtUtil.generateToken(DONO);
    }

    private static String chave(String segredo) {
        return "Bearer " + segredo;
    }

    private static IntegrationShoppingResponses.IntegrationAdvice conselho() {
        return new IntegrationShoppingResponses.IntegrationAdvice(
                Instant.parse("2026-11-11T12:00:00Z"),
                new IncomePatternResponse("NO_INCOME", "Cadastre uma fonte de renda",
                        LocalDate.of(2026, 11, 11), List.of(), null, null, null),
                new IntegrationShoppingResponses.FreeAfterSalary(true, LocalDate.of(2026, 12, 5), 24,
                        500000L, 12000L, 320000L, 180000L));
    }

    // ------------------------------------------------------------ a chave entra onde deve

    @Test
    @DisplayName("Chave válida lê o conselho do DONO da chave, com ETag")
    void conselhoDoDonoDaChave() {
        when(adviceService.advice(DONO)).thenReturn(new IntegrationAdviceService.Result(conselho(), "\"abc\""));

        client.get().uri(ADVICE).header(HttpHeaders.AUTHORIZATION, chave(CHAVE_DO_DONO))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ETAG, "\"abc\"")
                .expectBody()
                .jsonPath("$.generatedAt").isEqualTo("2026-11-11T12:00:00Z")
                .jsonPath("$.incomePattern.status").isEqualTo("NO_INCOME")
                .jsonPath("$.free.salaryKnown").isEqualTo(true)
                .jsonPath("$.free.freeCents").isEqualTo(180000);
        // O e-mail que chega ao serviço é o do dono da chave, e só ele
        verify(adviceService).advice(DONO);
    }

    @Test
    @DisplayName("If-None-Match com o ETag atual responde 304 sem corpo")
    void conselhoNaoModificado() {
        when(adviceService.advice(DONO)).thenReturn(new IntegrationAdviceService.Result(conselho(), "\"abc\""));

        client.get().uri(ADVICE)
                .header(HttpHeaders.AUTHORIZATION, chave(CHAVE_DO_DONO))
                .header(HttpHeaders.IF_NONE_MATCH, "\"abc\"")
                .exchange()
                .expectStatus().isNotModified()
                .expectHeader().valueEquals(HttpHeaders.ETAG, "\"abc\"")
                .expectBody().isEmpty();

        client.get().uri(ADVICE)
                .header(HttpHeaders.AUTHORIZATION, chave(CHAVE_DO_DONO))
                .header(HttpHeaders.IF_NONE_MATCH, "\"velho\"")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.free.freeCents").isEqualTo(180000);
    }

    @Test
    @DisplayName("Chave válida lê o histórico do DONO da chave, repassando cursor e limite")
    void historicoDoDonoDaChave() {
        UUID id = UUID.randomUUID();
        when(historyService.page(DONO, "abc", 2)).thenReturn(new IntegrationShoppingResponses.IntegrationTripPage(
                List.of(new IntegrationShoppingResponses.IntegrationTrip(id, "c1", UUID.randomUUID(), true,
                        "Mercado da Esquina", "CLOSED",
                        Instant.parse("2026-09-21T09:00:00Z"), Instant.parse("2026-09-21T10:00:00Z"),
                        null, 5840L, 5790L, 5840L, null, null, null, null, List.of(),
                        Instant.parse("2026-09-21T09:00:01Z"), Instant.parse("2026-09-21T10:00:01Z"))),
                "proximo", true));

        client.get().uri(TRIPS + "?cursor=abc&limit=2").header(HttpHeaders.AUTHORIZATION, chave(CHAVE_DO_DONO))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.trips[0].id").isEqualTo(id.toString())
                .jsonPath("$.trips[0].startedAt").isEqualTo("2026-09-21T09:00:00Z")
                .jsonPath("$.trips[0].paidTotalCents").isEqualTo(5840)
                .jsonPath("$.nextCursor").isEqualTo("proximo")
                .jsonPath("$.hasMore").isEqualTo(true);
    }

    @Test
    @DisplayName("A integração é só leitura: outro verbo é recusado mesmo com chave válida")
    void soLeitura() {
        client.post().uri(TRIPS).header(HttpHeaders.AUTHORIZATION, chave(CHAVE_DO_DONO))
                .exchange()
                .expectStatus().isForbidden();
        verifyNoInteractions(historyService);
    }

    // ------------------------------------------------------------ e não entra onde não deve

    @Test
    @DisplayName("JWT de sessão em rota de integração → 401, sem chegar ao serviço")
    void jwtNaoValeNaIntegracao() {
        client.get().uri(ADVICE).header(HttpHeaders.AUTHORIZATION, jwt())
                .exchange().expectStatus().isUnauthorized();
        client.get().uri(TRIPS).header(HttpHeaders.AUTHORIZATION, jwt())
                .exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(adviceService, historyService);
    }

    @Test
    @DisplayName("Chave em rota normal → 401: não lê o carrinho nem gere chaves")
    void chaveNaoValeForaDaIntegracao() {
        client.get().uri("/api/v1/shopping/trips").header(HttpHeaders.AUTHORIZATION, chave(CHAVE_DO_DONO))
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/integration-keys").header(HttpHeaders.AUTHORIZATION, chave(CHAVE_DO_DONO))
                .exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/integration-keys").header(HttpHeaders.AUTHORIZATION, chave(CHAVE_DO_DONO))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "outra"))
                .exchange().expectStatus().isUnauthorized();
        client.delete().uri("/api/v1/integration-keys/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, chave(CHAVE_DO_DONO))
                .exchange().expectStatus().isUnauthorized();

        verifyNoInteractions(cartService);
        verify(keyService, never()).list(any());
        verify(keyService, never()).create(any(), any());
        verify(keyService, never()).revoke(any(), any());
        // Fora da cadeia de integração a chave nem chega a ser conferida
        verify(keyService, never()).authenticate(any());
    }

    @Test
    @DisplayName("Revogada, desconhecida, malformada, JWT e sem cabeçalho: o mesmo 401, byte a byte")
    void recusaNeutra() {
        EntityExchangeResult<byte[]> semCabecalho = client.get().uri(TRIPS)
                .exchange().expectStatus().isUnauthorized().expectBody().returnResult();

        for (String autorizacao : List.of(
                chave(CHAVE_REVOGADA),
                chave("eco_" + "Z".repeat(43)),
                chave("eco_curta"),
                "Bearer ",
                "Basic qualquer-coisa",
                jwt())) {
            EntityExchangeResult<byte[]> recusa = client.get().uri(TRIPS)
                    .header(HttpHeaders.AUTHORIZATION, autorizacao)
                    .exchange().expectStatus().isUnauthorized().expectBody().returnResult();

            assertThat(recusa.getResponseBody()).as("corpo para %s", autorizacao)
                    .isEqualTo(semCabecalho.getResponseBody());
            assertThat(recusa.getResponseHeaders().get(HttpHeaders.WWW_AUTHENTICATE))
                    .as("WWW-Authenticate para %s", autorizacao)
                    .isEqualTo(semCabecalho.getResponseHeaders().get(HttpHeaders.WWW_AUTHENTICATE));
            assertThat(recusa.getResponseHeaders().getContentType())
                    .isEqualTo(semCabecalho.getResponseHeaders().getContentType());
        }
        verifyNoInteractions(historyService);
    }

    // ------------------------------------------------------------ gestão, com JWT

    @Test
    @DisplayName("POST com JWT cria e devolve o segredo; a lista não o traz")
    void gestaoComJwt() {
        IntegrationKeyResponses.IntegrationKeyItem item = new IntegrationKeyResponses.IntegrationKeyItem(
                UUID.randomUUID(), "UniOver", "eco_AAAAAAAA", OffsetDateTime.parse("2026-11-11T12:00:00Z"),
                null, null, false);
        when(keyService.create(DONO, "UniOver"))
                .thenReturn(new IntegrationKeyResponses.IntegrationKeyCreated(item, CHAVE_DO_DONO));
        when(keyService.list(DONO)).thenReturn(List.of(item));

        client.post().uri("/api/v1/integration-keys").header(HttpHeaders.AUTHORIZATION, jwt())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "UniOver"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.secret").isEqualTo(CHAVE_DO_DONO)
                .jsonPath("$.key.prefix").isEqualTo("eco_AAAAAAAA");

        client.get().uri("/api/v1/integration-keys").header(HttpHeaders.AUTHORIZATION, jwt())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("UniOver")
                .jsonPath("$[0].revoked").isEqualTo(false)
                .jsonPath("$[0].secret").doesNotExist()
                .jsonPath("$[0].keyHash").doesNotExist();
    }

    @Test
    @DisplayName("Nome em branco responde 400; DELETE com JWT responde 204")
    void validacaoERevogacao() {
        client.post().uri("/api/v1/integration-keys").header(HttpHeaders.AUTHORIZATION, jwt())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "  "))
                .exchange().expectStatus().isBadRequest();

        UUID id = UUID.randomUUID();
        client.delete().uri("/api/v1/integration-keys/" + id).header(HttpHeaders.AUTHORIZATION, jwt())
                .exchange().expectStatus().isNoContent();
        verify(keyService).revoke(DONO, id);
    }
}
