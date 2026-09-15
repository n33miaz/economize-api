package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.wish.WishRequests;
import br.com.economize.dto.wish.WishResponses;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.wish.WishContributionService;
import br.com.economize.service.wish.WishService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * As rotas dos Desejos (EC-140).
 *
 * <p>O serviço tinha teste; as rotas, nenhum — e é nelas que moram o corpo
 * validado, o status de criação e a amarração ao dono do token.
 */
@WebFluxTest(WishController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class })
@DisplayName("WishController — as rotas dos desejos")
class WishControllerTest {

    private static final String EMAIL = "teste@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private WishService wishService;

    @MockitoBean
    private WishContributionService contributionService;

    private String bearer() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }

    private WishResponses.WishItem item(String nome) {
        return new WishResponses.WishItem(UUID.randomUUID(), nome, new BigDecimal("18000.00"),
                BigDecimal.ZERO, null, "WISH", null, null, null, null, null);
    }

    @Test
    @DisplayName("GET / devolve a lista já projetada")
    void listaDesejos() {
        when(wishService.list(EMAIL)).thenReturn(
                new WishResponses.WishList(null, List.of(item("Moto"))));

        webTestClient.get().uri("/api/v1/wishes")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.wishes[0].name").isEqualTo("Moto")
                .jsonPath("$.wishes[0].status").isEqualTo("WISH");
    }

    @Test
    @DisplayName("GET /{id} passa o dono do TOKEN, nunca um id de usuário do cliente")
    void detalhaComODonoDoToken() {
        UUID id = UUID.randomUUID();
        when(wishService.get(eq(EMAIL), eq(id))).thenReturn(item("Casa"));

        webTestClient.get().uri("/api/v1/wishes/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("Casa");

        verify(wishService).get(EMAIL, id);
    }

    @Test
    @DisplayName("POST cria e responde 201")
    void criaDesejo() {
        when(wishService.create(eq(EMAIL), any(WishRequests.CreateWish.class)))
                .thenReturn(item("Moto"));

        webTestClient.post().uri("/api/v1/wishes")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Moto", "targetAmount", 18000))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.name").isEqualTo("Moto");
    }

    @Test
    @DisplayName("Desejo sem nome é 400, e o serviço nem é chamado")
    void nomeObrigatorio() {
        webTestClient.post().uri("/api/v1/wishes")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("targetAmount", 18000))
                .exchange()
                .expectStatus().isBadRequest();

        verify(wishService, never()).create(any(), any());
    }

    @Test
    @DisplayName("Valor zero ou negativo não é desejo")
    void valorPrecisaSerPositivo() {
        for (Object valor : List.of(0, -50)) {
            webTestClient.post().uri("/api/v1/wishes")
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("name", "Moto", "targetAmount", valor))
                    .exchange()
                    .expectStatus().isBadRequest();
        }

        verify(wishService, never()).create(any(), any());
    }

    @Test
    @DisplayName("PATCH atualiza pelo par (dono, id)")
    void atualizaDesejo() {
        UUID id = UUID.randomUUID();
        when(wishService.update(eq(EMAIL), eq(id), any())).thenReturn(item("Moto nova"));

        webTestClient.patch().uri("/api/v1/wishes/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Moto nova"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("Moto nova");
    }

    @Test
    @DisplayName("POST /{id}/purchase fecha o ciclo do desejo")
    void marcaComprado() {
        UUID id = UUID.randomUUID();
        when(wishService.purchase(eq(EMAIL), eq(id), any())).thenReturn(item("Moto"));

        webTestClient.post().uri("/api/v1/wishes/" + id + "/purchase")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isOk();

        verify(wishService).purchase(eq(EMAIL), eq(id), any());
    }

    @Test
    @DisplayName("DELETE responde 204 e não devolve corpo")
    void apagaDesejo() {
        UUID id = UUID.randomUUID();

        webTestClient.delete().uri("/api/v1/wishes/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        verify(wishService).delete(EMAIL, id);
    }

    /**
     * O aporte era a única rota deste controlador sem {@code @Valid} — e o
     * record não tinha uma restrição sequer, enquanto os dois vizinhos tinham.
     * O valor atravessava até o Postgres e estourava a coluna: o usuário recebia
     * "erro inesperado, tente novamente mais tarde", que é mentira, porque
     * tentar mais tarde dá exatamente igual.
     */
    @Test
    @DisplayName("Aporte acima da faixa da coluna responde 400, e o serviço nem é chamado")
    void aporteGrandeDemaisNaoChegaNoBanco() {
        UUID id = UUID.randomUUID();

        // 16 dígitos: um a mais do que a coluna NUMERIC(19,4) comporta. O valor
        // de 15 dígitos PASSA por aqui de propósito — ele cabe sozinho, e quem
        // segura o estouro da SOMA com o saldo é o service
        webTestClient.post().uri("/api/v1/wishes/" + id + "/contributions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amount", new BigDecimal("9999999999999999")))
                .exchange()
                .expectStatus().isBadRequest();

        verify(contributionService, never()).contribute(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Observação acima de 200 responde 400 — é o tamanho da coluna")
    void observacaoCompridaDemaisNaoChegaNoBanco() {
        UUID id = UUID.randomUUID();

        webTestClient.post().uri("/api/v1/wishes/" + id + "/contributions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amount", new BigDecimal("10"), "note", "a".repeat(201)))
                .exchange()
                .expectStatus().isBadRequest();

        verify(contributionService, never()).contribute(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Aporte sem valor responde 400")
    void aporteSemValorNaoPassa() {
        UUID id = UUID.randomUUID();

        webTestClient.post().uri("/api/v1/wishes/" + id + "/contributions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("note", "sem valor nenhum"))
                .exchange()
                .expectStatus().isBadRequest();

        verify(contributionService, never()).contribute(any(), any(), any(), any(), any());
    }

    /**
     * A guarda acima tinha como cobrar caro: aporte NEGATIVO é entrada legítima
     * — é o desfazer honesto, sai do saldo e fica no histórico. Um teto escrito
     * com {@code @DecimalMin} teria fechado a porta do valor absurdo e a do
     * desfazer junto, e ninguém perceberia até alguém tentar corrigir um dedo
     * errado.
     */
    @Test
    @DisplayName("Aporte negativo continua passando — é o desfazer, não um erro")
    void aporteNegativoContinuaPassando() {
        UUID id = UUID.randomUUID();
        WishContributionService.Item item = new WishContributionService.Item(
                UUID.randomUUID(), new BigDecimal("-100.00"),
                br.com.economize.model.WishContribution.Origin.DECLARED, null, null,
                java.time.OffsetDateTime.now());
        when(contributionService.contribute(eq(EMAIL), eq(id), any(), any(), any()))
                .thenReturn(new WishContributionService.Result(item, new BigDecimal("150.50")));

        webTestClient.post().uri("/api/v1/wishes/" + id + "/contributions")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amount", new BigDecimal("-100.00")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.savedAmount").isEqualTo(150.50);
    }

    @Test
    @DisplayName("Sem token, nenhuma rota de desejo responde")
    void semTokenNaoResponde() {
        webTestClient.get().uri("/api/v1/wishes")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(wishService, never()).list(any());
    }
}
