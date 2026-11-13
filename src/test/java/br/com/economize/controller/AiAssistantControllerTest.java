package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.AiAssistantService;
import br.com.economize.service.ai.AiProviderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Falha do provedor de IA no chat chega ao app como 502 com motivo, não como o
 * 500 genérico de 12/11/2026 (modelo do servidor aposentado pelo Google).
 */
@WebFluxTest(AiAssistantController.class)
@Import({CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class})
class AiAssistantControllerTest {

    private static final String EMAIL = "teste@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private AiAssistantService service;

    @Test
    @DisplayName("POST /chat - Modelo recusado pelo provedor responde 502 com motivo MODEL e texto nosso")
    void chatShouldReportProviderFailure() {
        when(service.askAssistant(anyString(), anyString(), any())).thenReturn(Mono.error(
                new AiProviderException(AiProviderException.Reason.MODEL,
                        "O provedor de IA não reconheceu o modelo configurado no servidor.")));

        webTestClient.post()
                .uri("/api/v1/chat")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtUtil.generateToken(EMAIL))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"quanto gastei?\"}")
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.reason").isEqualTo("MODEL")
                .jsonPath("$.detail").isEqualTo("O provedor de IA não reconheceu o modelo configurado no servidor.")
                .jsonPath("$.title").isEqualTo("Erro no Provedor de IA");
    }
}
