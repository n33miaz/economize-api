package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.user.ChangePasswordRequest;
import br.com.economize.repository.UserRepository;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.PasswordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@WebFluxTest(UserController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class})
class UserControllerTest {

    private static final String EMAIL = "teste@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private PasswordService passwordService;

    @Test
    @DisplayName("POST /me/change-password - Autenticado com senha atual correta deve retornar 204")
    void changePasswordShouldReturn204OnSuccess() {
        webTestClient.post()
                .uri("/api/v1/users/me/change-password")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChangePasswordRequest("SenhaAtual1", "NovaSenha123"))
                .exchange()
                .expectStatus().isNoContent();

        verify(passwordService).changePassword(EMAIL, "SenhaAtual1", "NovaSenha123");
    }

    @Test
    @DisplayName("POST /me/change-password - Senha atual incorreta deve retornar 400")
    void changePasswordShouldReturn400WhenCurrentPasswordDoesNotMatch() {
        doThrow(new IllegalArgumentException("Senha atual incorreta"))
                .when(passwordService).changePassword(anyString(), anyString(), anyString());

        webTestClient.post()
                .uri("/api/v1/users/me/change-password")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChangePasswordRequest("errada", "NovaSenha123"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Senha atual incorreta");
    }

    @Test
    @DisplayName("POST /me/change-password - Senha nova curta deve retornar 400 sem chamar o service")
    void changePasswordShouldRejectShortNewPassword() {
        webTestClient.post()
                .uri("/api/v1/users/me/change-password")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChangePasswordRequest("SenhaAtual1", "curta"))
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(passwordService);
    }

    @Test
    @DisplayName("POST /me/change-password - Sem token deve retornar 401")
    void changePasswordShouldRequireAuthentication() {
        webTestClient.post()
                .uri("/api/v1/users/me/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChangePasswordRequest("SenhaAtual1", "NovaSenha123"))
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(passwordService);
    }

    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }
}
