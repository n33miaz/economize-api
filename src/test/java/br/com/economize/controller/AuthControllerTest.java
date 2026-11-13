package br.com.economize.controller;

import br.com.economize.model.User;
import java.util.Map;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import br.com.economize.config.CorsConfig;
import br.com.economize.dto.auth.ForgotPasswordRequest;
import br.com.economize.dto.auth.ResetPasswordRequest;
import br.com.economize.repository.UserRepository;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.security.SessionVersionService;
import br.com.economize.service.MfaService;
import br.com.economize.service.TrustedDeviceService;
import br.com.economize.service.PasswordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@WebFluxTest(AuthController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class})
class AuthControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private PasswordService passwordService;

    // O login consulta o segundo fator; sem o bean o contexto do WebFluxTest
    // nem sobe. O login em dois passos tem suite propria: AuthMfaLoginTest
    @MockitoBean
    private MfaService mfaService;

    // O login consulta os aparelhos conhecidos antes de exigir o segundo passo
    @MockitoBean
    private TrustedDeviceService deviceService;

    // "Sair de todos os aparelhos" (V45). A revogação de verdade, com banco e
    // cache, é provada no SessionVersionRevocationAuthTest
    @MockitoBean
    private SessionVersionService sessionVersions;

    @Test
    @DisplayName("POST /forgot-password - Deve responder 202 com mensagem neutra")
    void forgotPasswordShouldReturnNeutral202() {
        webTestClient.post()
                .uri("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ForgotPasswordRequest("ana@economize.dev"))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("Se o e-mail existir, enviaremos instruções para redefinir a senha.");

        verify(passwordService).forgotPassword("ana@economize.dev");
    }

    @Test
    @DisplayName("POST /forgot-password - E-mail malformado deve retornar 400 sem chamar o service")
    void forgotPasswordShouldRejectMalformedEmail() {
        webTestClient.post()
                .uri("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ForgotPasswordRequest("nao-e-um-email"))
                .exchange()
                .expectStatus().isBadRequest();

        verify(passwordService, never()).forgotPassword(anyString());
    }

    @Test
    @DisplayName("POST /reset-password - Token válido deve retornar 204")
    void resetPasswordShouldReturn204OnSuccess() {
        webTestClient.post()
                .uri("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ResetPasswordRequest("token-valido", "NovaSenha123"))
                .exchange()
                .expectStatus().isNoContent();

        verify(passwordService).resetPassword("token-valido", "NovaSenha123");
    }

    @Test
    @DisplayName("POST /reset-password - Token inválido deve retornar 400 ProblemDetail neutro")
    void resetPasswordShouldReturn400ForInvalidToken() {
        doThrow(new IllegalArgumentException("Token inválido ou expirado"))
                .when(passwordService).resetPassword(anyString(), anyString());

        webTestClient.post()
                .uri("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ResetPasswordRequest("token-invalido", "NovaSenha123"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Token inválido ou expirado");
    }

    @Test
    @DisplayName("POST /reset-password - Senha com menos de 8 caracteres deve retornar 400")
    void resetPasswordShouldRejectShortPassword() {
        webTestClient.post()
                .uri("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ResetPasswordRequest("token-valido", "curta"))
                .exchange()
                .expectStatus().isBadRequest();

        Mockito.verifyNoInteractions(passwordService);
    }

    @Test
    @DisplayName("POST /logout-all - sem token responde 401 e não revoga nada")
    void logoutAllRequiresAuthentication() {
        webTestClient.post()
                .uri("/api/v1/auth/logout-all")
                .exchange()
                .expectStatus().isUnauthorized();

        Mockito.verifyNoInteractions(sessionVersions);
    }

    // ------------------------------------------------------------ cadastro

    private WebTestClient.ResponseSpec registrar(String nome, String email, String senha) {
        return webTestClient.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", nome, "email", email, "password", senha))
                .exchange();
    }

    @Test
    @DisplayName("POST /register - Nome acima de 100 caracteres é 400, não o 409 do banco")
    void registerShouldRejectNameAboveColumn() {
        // users.name é VARCHAR(100): antes passava no @Size(120) e estourava no
        // INSERT, que o handler traduzia como "conflito"
        registrar("a".repeat(101), "ana@economize.dev", "SenhaForte123")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(containsString("100 caracteres"));

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("POST /register - E-mail acima de 150 caracteres é 400")
    void registerShouldRejectEmailAboveColumn() {
        String email = "b".repeat(60) + "@" + "c".repeat(60) + "." + "d".repeat(30) + ".dev";

        registrar("Ana", email, "SenhaForte123")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(containsString("150 caracteres"));

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("POST /register - Senha acima de 72 bytes é 400 em português, e não 409 de e-mail repetido")
    void registerShouldRejectPasswordAbove72Bytes() {
        // 20 emojis: 40 caracteres para o @Size, 80 bytes para o BCrypt
        registrar("Ana", "ana@economize.dev", "\uD83D\uDE00".repeat(20))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(containsString("longa demais"));

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("POST /register - E-mail já cadastrado continua 409")
    void registerShouldAnswer409OnlyForDuplicateEmail() {
        when(userRepository.findByEmail("ana@economize.dev"))
                .thenReturn(Optional.of(User.builder().email("ana@economize.dev").build()));

        registrar("Ana", "ana@economize.dev", "SenhaForte123")
                .expectStatus().isEqualTo(409);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("POST /register - Outro IllegalArgumentException não vira 409 de duplicidade")
    void registerShouldNotTurnOtherErrorsInto409() {
        // era o caso do BCrypt: qualquer IllegalArgumentException respondia
        // "e-mail já existe" para um e-mail novo
        when(userRepository.save(any())).thenThrow(new IllegalArgumentException("falha qualquer"));

        registrar("Ana", "ana@economize.dev", "SenhaForte123")
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("POST /reset-password - Senha acima de 72 bytes é 400 em português")
    void resetPasswordShouldRejectPasswordAbove72Bytes() {
        webTestClient.post()
                .uri("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ResetPasswordRequest("token-valido", "\u00E9".repeat(37)))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(containsString("longa demais"));

        verify(passwordService, never()).resetPassword(anyString(), anyString());
    }
}
