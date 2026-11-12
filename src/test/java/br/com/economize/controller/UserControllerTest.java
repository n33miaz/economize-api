package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.user.ChangePasswordRequest;
import br.com.economize.model.Plan;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.ReportRepository;
import br.com.economize.repository.TransactionRepository;
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

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    @MockitoBean
    private BankTransactionRepository bankTransactionRepository;

    @MockitoBean
    private TransactionRepository transactionRepository;

    @MockitoBean
    private ReportRepository reportRepository;

    @Test
    @DisplayName("GET /me - Conta FREE vê anúncios: adsEnabled=true, plan=FREE, planUntil nulo")
    void meShouldEnableAdsForFreePlan() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(Plan.FREE, null)));

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email").isEqualTo(EMAIL)
                .jsonPath("$.plan").isEqualTo("FREE")
                .jsonPath("$.planUntil").isEmpty()
                .jsonPath("$.adsEnabled").isEqualTo(true)
                .jsonPath("$.mustChangePassword").isEqualTo(false);
    }

    @Test
    @DisplayName("GET /me - PLUS vigente (sem prazo ou com prazo futuro) não vê anúncios")
    void meShouldDisableAdsForActivePlus() {
        when(userRepository.findByEmail(EMAIL))
                .thenReturn(Optional.of(user(Plan.PLUS, null)))
                .thenReturn(Optional.of(user(Plan.PLUS, OffsetDateTime.now().plusDays(10))));

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.plan").isEqualTo("PLUS")
                .jsonPath("$.adsEnabled").isEqualTo(false);

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.planUntil").isNotEmpty()
                .jsonPath("$.adsEnabled").isEqualTo(false);
    }

    @Test
    @DisplayName("GET /me - PLUS vencido volta a ver anúncios, mas a coluna continua PLUS")
    void meShouldEnableAdsForExpiredPlus() {
        when(userRepository.findByEmail(EMAIL))
                .thenReturn(Optional.of(user(Plan.PLUS, OffsetDateTime.now().minusMinutes(1))));

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.plan").isEqualTo("PLUS")
                .jsonPath("$.adsEnabled").isEqualTo(true);
    }

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

    @Test
    @DisplayName("GET /me/stats - conta as três fontes sem baixar nenhuma lista")
    void statsShouldCountWithoutLoadingLists() {
        User user = user(Plan.FREE, null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.countByUserId(user.getId())).thenReturn(1752L);
        when(transactionRepository.countByUserId(user.getId())).thenReturn(12L);
        when(reportRepository.countByUserId(user.getId())).thenReturn(3L);

        webTestClient.get()
                .uri("/api/v1/users/me/stats")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.bankTransactions").isEqualTo(1752)
                .jsonPath("$.walletTransactions").isEqualTo(12)
                .jsonPath("$.reports").isEqualTo(3);

        // O ponto do endpoint: contagem, e nenhum findAll. Se alguém voltar a
        // buscar as listas aqui, os 100 KB do extrato voltam com ele
        verify(bankTransactionRepository).countByUserId(user.getId());
        verify(bankTransactionRepository, never()).findAll();
        verify(transactionRepository, never()).findAll();
        verify(reportRepository, never()).findAll();
    }

    @Test
    @DisplayName("GET /me/stats - sem token, 401")
    void statsShouldRequireAuthentication() {
        webTestClient.get()
                .uri("/api/v1/users/me/stats")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(bankTransactionRepository, transactionRepository, reportRepository);
    }

    private User user(Plan plan, OffsetDateTime planUntil) {
        return User.builder()
                .id(UUID.randomUUID())
                .name("Teste")
                .email(EMAIL)
                .password("hash")
                .plan(plan)
                .planUntil(planUntil)
                .build();
    }

    @Test
    @DisplayName("GET /me/connection - Sem token responde 401")
    void connectionShouldRequireAuthentication() {
        webTestClient.get()
                .uri("/api/v1/users/me/connection")
                .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("GET /me/connection - Devolve a cadeia na ordem, os cabeçalhos de borda e o IP escolhido")
    void connectionShouldReturnForwardingChain() {
        webTestClient.get()
                .uri("/api/v1/users/me/connection")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                // duas linhas do mesmo cabeçalho: a cadeia é a junção, na ordem
                .header("X-Forwarded-For", "1.2.3.4, 203.0.113.7", "172.71.10.5, 10.0.0.1", "lixo")
                .header("CF-Connecting-IP", "198.51.100.9")
                .header("True-Client-IP", "198.51.100.10")
                .header("X-Real-IP", "198.51.100.11")
                .header("Forwarded", "for=198.51.100.9;proto=https")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.forwardedFor.length()").isEqualTo(5)
                .jsonPath("$.forwardedFor[0].address").isEqualTo("1.2.3.4")
                .jsonPath("$.forwardedFor[0].kind").isEqualTo("PUBLIC")
                .jsonPath("$.forwardedFor[1].address").isEqualTo("203.0.113.7")
                .jsonPath("$.forwardedFor[1].kind").isEqualTo("PUBLIC")
                .jsonPath("$.forwardedFor[2].address").isEqualTo("172.71.10.5")
                .jsonPath("$.forwardedFor[2].kind").isEqualTo("CLOUDFLARE")
                .jsonPath("$.forwardedFor[3].address").isEqualTo("10.0.0.1")
                .jsonPath("$.forwardedFor[3].kind").isEqualTo("INTERNAL")
                .jsonPath("$.forwardedFor[4].kind").isEqualTo("UNREADABLE")
                .jsonPath("$.cfConnectingIp").isEqualTo("198.51.100.9")
                .jsonPath("$.trueClientIp").isEqualTo("198.51.100.10")
                .jsonPath("$.realIp").isEqualTo("198.51.100.11")
                .jsonPath("$.forwarded[0]").isEqualTo("for=198.51.100.9;proto=https")
                // salto ilegível no trecho da plataforma: o resolvedor não adivinha,
                // e sem soquete (teste) não há IP — o diagnóstico mostra isso mesmo
                .jsonPath("$.resolvedClientIp").isEmpty();

        webTestClient.get()
                .uri("/api/v1/users/me/connection")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .header("X-Forwarded-For", "1.2.3.4, 203.0.113.7, 172.71.10.5, 10.0.0.1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                // o primeiro, pela direita, que não é interno nem Cloudflare
                .jsonPath("$.resolvedClientIp").isEqualTo("203.0.113.7");

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("GET /me/connection - Não devolve Authorization nem Cookie, e aguenta cabeçalho ausente")
    void connectionShouldNotLeakCredentials() {
        String token = bearerToken();

        String body = webTestClient.get()
                .uri("/api/v1/users/me/connection")
                .header(HttpHeaders.AUTHORIZATION, token)
                .header(HttpHeaders.COOKIE, "sessao=segredo-do-cookie")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain(token.substring("Bearer ".length()))
                .doesNotContainIgnoringCase("authorization")
                .doesNotContain("segredo-do-cookie")
                .contains("\"forwardedFor\":[]")
                .contains("\"cfConnectingIp\":null");
    }

    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }
}
