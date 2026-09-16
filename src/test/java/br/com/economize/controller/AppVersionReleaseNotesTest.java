package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.security.AppVersionFilter;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.AppVersionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * As notas da versão do OPERADOR até o JSON, pelo nome da propriedade.
 *
 * <p>O parse em si é testado na unidade ({@code AppVersionServiceTest}); o
 * que esta fatia prova é a amarração que a unidade não vê: que
 * {@code economize.app.release-notes} é o nome que o serviço lê, e que a
 * lista sai no corpo como {@code notes}. Um erro de digitação em qualquer um
 * dos dois lados deixaria a folha do app muda para sempre, sem erro em lugar
 * nenhum — e ninguém repararia até o release seguinte.
 */
@WebFluxTest(AppVersionController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class,
        AppVersionService.class })
@TestPropertySource(properties = "economize.app.release-notes=Mercado com mais moedas | Home com parcelamentos")
class AppVersionReleaseNotesTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("GET /app/version devolve as notas escritas na propriedade, na ordem")
    void devolveAsNotasDaPropriedade() {
        webTestClient.get()
                .uri("/api/v1/app/version")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.notes.length()").isEqualTo(2)
                .jsonPath("$.notes[0]").isEqualTo("Mercado com mais moedas")
                .jsonPath("$.notes[1]").isEqualTo("Home com parcelamentos");
    }

    @Test
    @DisplayName("O app antigo continua lendo o resto do documento — o campo novo é aditivo")
    void campoNovoNaoQuebraOResto() {
        webTestClient.get()
                .uri("/api/v1/app/version")
                .header(AppVersionFilter.VERSION_HEADER, "0.0.1")
                .header(AppVersionFilter.PLATFORM_HEADER, "android")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.minVersion").exists()
                .jsonPath("$.latestVersion").exists()
                .jsonPath("$.downloadUrl").exists();
    }
}
