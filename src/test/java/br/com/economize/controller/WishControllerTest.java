package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.wish.WishRequests;
import br.com.economize.dto.wish.WishResponses;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
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

    @Test
    @DisplayName("Sem token, nenhuma rota de desejo responde")
    void semTokenNaoResponde() {
        webTestClient.get().uri("/api/v1/wishes")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(wishService, never()).list(any());
    }
}
