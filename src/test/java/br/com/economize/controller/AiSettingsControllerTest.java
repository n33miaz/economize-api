package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.ai.AiKeyTestResponse;
import br.com.economize.dto.ai.AiProviderCatalogResponse;
import br.com.economize.dto.ai.AiSettingsResponse;
import br.com.economize.dto.ai.SaveAiSettingsRequest;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.exception.ServiceUnavailableException;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.ai.UserAiSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest(AiSettingsController.class)
@Import({CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class})
class AiSettingsControllerTest {

    private static final String EMAIL = "teste@economize.app";
    private static final String CHAVE = "sk-proj-chave-que-nao-pode-vazar-1234";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserAiSettingsService service;

    @Test
    @DisplayName("GET /settings - A chave NUNCA volta: só provedor, modelo e 4 caracteres")
    void getShouldNeverReturnTheKey() {
        when(service.current(EMAIL)).thenReturn(new AiSettingsResponse(
                "USER", "ANTHROPIC", "claude-sonnet-4-5", "1234", "OK", true,
                OffsetDateTime.parse("2026-08-16T12:00:00Z")));

        webTestClient.get()
                .uri("/api/v1/ai/settings")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.source").isEqualTo("USER")
                .jsonPath("$.provider").isEqualTo("ANTHROPIC")
                .jsonPath("$.model").isEqualTo("claude-sonnet-4-5")
                .jsonPath("$.keyLast4").isEqualTo("1234")
                .jsonPath("$.keyStatus").isEqualTo("OK")
                .jsonPath("$.apiKey").doesNotExist()
                .jsonPath("$.apiKeyCipher").doesNotExist()
                .jsonPath("$.key").doesNotExist();
    }

    @Test
    @DisplayName("GET /settings - Sem chave própria, descreve a chave do servidor")
    void getShouldDescribeServerFallback() {
        when(service.current(EMAIL)).thenReturn(new AiSettingsResponse(
                "SERVER", "GEMINI", "gemini-2.0-flash", null, "SERVER_KEY", true, null));

        webTestClient.get()
                .uri("/api/v1/ai/settings")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.source").isEqualTo("SERVER")
                .jsonPath("$.provider").isEqualTo("GEMINI")
                .jsonPath("$.keyStatus").isEqualTo("SERVER_KEY")
                .jsonPath("$.keyLast4").isEmpty();
    }

    @Test
    @DisplayName("GET /settings - Sem token responde 401 e o serviço nem é chamado")
    void getShouldRequireAuthentication() {
        webTestClient.get()
                .uri("/api/v1/ai/settings")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("PUT /settings - Salva e devolve o estado novo, ainda sem a chave em lugar nenhum")
    void putShouldSaveWithoutEchoingTheKey() {
        when(service.save(eq(EMAIL), any(SaveAiSettingsRequest.class))).thenReturn(new AiSettingsResponse(
                "USER", "OPENAI", "gpt-4o-mini", "1234", "OK", true,
                OffsetDateTime.parse("2026-08-16T12:00:00Z")));

        webTestClient.put()
                .uri("/api/v1/ai/settings")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveAiSettingsRequest("OPENAI", "gpt-4o-mini", CHAVE))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .doesNotContain(CHAVE)
                        .doesNotContain("apiKey")
                        .contains("\"keyLast4\":\"1234\""));
    }

    @Test
    @DisplayName("PUT /settings - Provedor inválido responde 400 em ProblemDetail, sem a chave no corpo")
    void putShouldReturnBadRequestForUnknownProvider() {
        when(service.save(eq(EMAIL), any(SaveAiSettingsRequest.class)))
                .thenThrow(new IllegalArgumentException(
                        "Provedor inválido. Aceitos: GEMINI, OPENAI, ANTHROPIC, OPENROUTER"));

        webTestClient.put()
                .uri("/api/v1/ai/settings")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveAiSettingsRequest("llama-caseira", "x", CHAVE))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("Provedor inválido")
                        .doesNotContain(CHAVE));
    }

    @Test
    @DisplayName("PUT /settings - Modelo inválido responde 400 com a lista aceita, sem a chave no corpo")
    void putShouldReturnBadRequestForUnknownModel() {
        when(service.save(eq(EMAIL), any(SaveAiSettingsRequest.class)))
                .thenThrow(new IllegalArgumentException(
                        "Modelo inválido para OPENAI. Aceitos: gpt-4o-mini, gpt-4o"));

        webTestClient.put()
                .uri("/api/v1/ai/settings")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveAiSettingsRequest("OPENAI", "gpt-inexistente", CHAVE))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("Modelo inválido")
                        .doesNotContain(CHAVE));
    }

    @Test
    @DisplayName("PUT /settings - Corpo sem chave nem provedor responde 400 antes de chamar o serviço")
    void putShouldValidatePayload() {
        webTestClient.put()
                .uri("/api/v1/ai/settings")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveAiSettingsRequest("  ", "gpt-4o-mini", "  "))
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("PUT /settings - Instalação sem cofre responde 503, não 500")
    void putShouldReturnServiceUnavailableWithoutVault() {
        when(service.save(eq(EMAIL), any(SaveAiSettingsRequest.class)))
                .thenThrow(new ServiceUnavailableException("Esta instalação não aceita chave própria de IA"));

        webTestClient.put()
                .uri("/api/v1/ai/settings")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SaveAiSettingsRequest("OPENAI", "gpt-4o-mini", CHAVE))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Serviço Indisponível");
    }

    @Test
    @DisplayName("DELETE /settings - Remove e responde 204")
    void deleteShouldReturnNoContent() {
        webTestClient.delete()
                .uri("/api/v1/ai/settings")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isNoContent();

        verify(service).delete(EMAIL);
    }

    @Test
    @DisplayName("DELETE /settings - Configuração inexistente para ESTA conta responde 404, nunca 403")
    void deleteShouldReturnNotFoundForForeignOrMissingSettings() {
        doThrow(new ResourceNotFoundException("Nenhuma configuração de IA cadastrada"))
                .when(service).delete(EMAIL);

        webTestClient.delete()
                .uri("/api/v1/ai/settings")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Não Encontrado");
    }

    @Test
    @DisplayName("GET /providers - Catálogo para o app montar o seletor sem falar com provedor nenhum")
    void providersShouldReturnCatalog() {
        when(service.catalog()).thenReturn(new AiProviderCatalogResponse(true, List.of(
                new AiProviderCatalogResponse.ProviderOption("GEMINI", "Google Gemini",
                        "gemini-2.0-flash", List.of("gemini-2.0-flash", "gemini-2.5-flash"),
                        "https://aistudio.google.com/apikey"))));

        webTestClient.get()
                .uri("/api/v1/ai/providers")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.byokAvailable").isEqualTo(true)
                .jsonPath("$.providers[0].id").isEqualTo("GEMINI")
                .jsonPath("$.providers[0].defaultModel").isEqualTo("gemini-2.0-flash")
                .jsonPath("$.providers[0].models[1]").isEqualTo("gemini-2.5-flash")
                .jsonPath("$.providers[0].apiKeyUrl").isEqualTo("https://aistudio.google.com/apikey");
    }

    @Test
    @DisplayName("POST /settings/test - Chave recusada responde 200 com ok=false, não erro de HTTP")
    void testShouldReturnOkFalseOnRefusal() {
        when(service.test(eq(EMAIL), any())).thenReturn(new AiKeyTestResponse(
                false, "OPENAI", "gpt-4o-mini", "AUTH", "O provedor recusou a chave cadastrada.", 412));

        webTestClient.post()
                .uri("/api/v1/ai/settings/test")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.ok").isEqualTo(false)
                .jsonPath("$.reason").isEqualTo("AUTH")
                .jsonPath("$.provider").isEqualTo("OPENAI");
    }

    @Test
    @DisplayName("POST /settings/test - Sem corpo testa a chave já cadastrada")
    void testShouldAcceptEmptyBody() {
        when(service.test(eq(EMAIL), any())).thenReturn(new AiKeyTestResponse(
                true, "GEMINI", "gemini-2.0-flash", null, "Chave aceita pelo provedor.", 250));

        webTestClient.post()
                .uri("/api/v1/ai/settings/test")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.ok").isEqualTo(true)
                .jsonPath("$.reason").isEmpty();
    }

    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }
}
