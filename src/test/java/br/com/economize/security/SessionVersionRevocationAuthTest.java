package br.com.economize.security;

import br.com.economize.model.PasswordResetToken;
import br.com.economize.model.User;
import br.com.economize.repository.PasswordResetTokenRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.TrustedDeviceService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Revogação de sessões (V45) com a aplicação inteira: filtro de verdade, banco
 * H2, cache de verdade.
 *
 * <p>Os testes de fatia ({@code @WebFluxTest}) montam o filtro sem a camada de
 * dados e por isso não conferem a versão; é aqui que fica provado que um token
 * revogado leva 401. Cada teste usa um e-mail próprio porque o banco em memória
 * é compartilhado entre os testes do mesmo contexto.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Revogação de sessões pela versão da sessão")
class SessionVersionRevocationAuthTest {

    private static final String PASSWORD = "senha-antiga-123";

    @Autowired
    private WebTestClient client;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository resetTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TrustedDeviceService deviceService;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.issuer}")
    private String issuer;

    @Test
    @DisplayName("trocar a senha derruba os tokens antigos; o token devolvido pela troca vale")
    void changingThePasswordRevokesOldTokens() {
        User user = newUser();
        String old = login(user.getEmail(), PASSWORD);
        // outro aparelho logado na mesma conta
        String otherDevice = jwtUtil.generateToken(userRepository.findByEmail(user.getEmail()).orElseThrow());
        me(old).expectStatus().isOk();
        me(otherDevice).expectStatus().isOk();

        String returned = client.post().uri("/api/v1/users/me/change-password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + old)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("currentPassword", PASSWORD, "newPassword", "senha-nova-456"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody()
                .get("token").toString();

        me(old).expectStatus().isUnauthorized();
        me(otherDevice).expectStatus().isUnauthorized();
        // quem trocou segue logado com o token da resposta, sem novo login
        me(returned).expectStatus().isOk();
        me(login(user.getEmail(), "senha-nova-456")).expectStatus().isOk();
    }

    @Test
    @DisplayName("redefinir a senha pelo e-mail derruba o token antigo")
    void resettingThePasswordRevokesOldTokens() throws Exception {
        User user = newUser();
        String old = login(user.getEmail(), PASSWORD);
        me(old).expectStatus().isOk();

        String raw = "token-de-recuperacao-" + UUID.randomUUID();
        resetTokenRepository.save(PasswordResetToken.builder()
                .user(user)
                .tokenHash(sha256Hex(raw))
                .expiresAt(OffsetDateTime.now().plusMinutes(10))
                .build());

        client.post().uri("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", raw, "newPassword", "senha-nova-456"))
                .exchange()
                .expectStatus().isNoContent();

        me(old).expectStatus().isUnauthorized();
        me(login(user.getEmail(), "senha-nova-456")).expectStatus().isOk();
    }

    @Test
    @DisplayName("logout-all derruba na hora o token que pediu e os outros, mesmo já em cache")
    void logoutAllRevokesEverySessionImmediately() {
        User user = newUser();
        String mine = login(user.getEmail(), PASSWORD);
        // segundo "aparelho": token emitido por outro caminho, na mesma versão
        String other = jwtUtil.generateToken(userRepository.findByEmail(user.getEmail()).orElseThrow());
        // as duas chamadas põem a versão 0 no cache: o teste só prova a
        // invalidação se o valor velho estiver lá antes da revogação
        me(mine).expectStatus().isOk();
        me(other).expectStatus().isOk();

        client.post().uri("/api/v1/auth/logout-all")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + mine)
                .exchange()
                .expectStatus().isNoContent();

        // sem espera nenhuma: o cache desta instância foi descartado no commit
        me(mine).expectStatus().isUnauthorized();
        me(other).expectStatus().isUnauthorized();
        assertThat(userRepository.findByEmail(user.getEmail()).orElseThrow().getSessionVersion()).isEqualTo(1);
        me(login(user.getEmail(), PASSWORD)).expectStatus().isOk();
    }

    @Test
    @DisplayName("logout-all sem token responde 401")
    void logoutAllRequiresASession() {
        client.post().uri("/api/v1/auth/logout-all")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("token sem a claim sv (de antes do deploy) vale só enquanto a conta está na versão 0")
    void legacyTokenWithoutClaimIsAcceptedOnlyAtVersionZero() {
        User user = newUser();
        String legacy = legacyToken(user.getEmail());
        me(legacy).expectStatus().isOk();

        logoutAll(login(user.getEmail(), PASSWORD));

        me(legacy).expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("token de conta que não existe não autentica")
    void tokenOfUnknownAccountIsRefused() {
        me(jwtUtil.generateToken("ninguem-" + UUID.randomUUID() + "@economize.test"))
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("depois do logout-all, aparelho lembrado deixa de dispensar o segundo fator")
    void logoutAllForgetsTrustedDevices() {
        User user = newUser();
        String deviceToken = deviceService.remember(user, "celular", "203.0.113.7");
        TrustedDeviceService.DeviceHint hint =
                new TrustedDeviceService.DeviceHint(deviceToken, "celular", "203.0.113.7");
        assertThat(deviceService.isTrusted(user, hint)).isTrue();

        logoutAll(login(user.getEmail(), PASSWORD));

        assertThat(deviceService.isTrusted(user, hint)).isFalse();
    }

    private User newUser() {
        return userRepository.save(User.builder()
                .name("Sessão")
                .email("sessao-" + UUID.randomUUID() + "@economize.test")
                .password(passwordEncoder.encode(PASSWORD))
                .build());
    }

    private String login(String email, String password) {
        String token = client.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", password))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody()
                .get("token").toString();
        assertThat(token).isNotBlank();
        return token;
    }

    private void logoutAll(String token) {
        client.post().uri("/api/v1/auth/logout-all")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isNoContent();
    }

    private WebTestClient.ResponseSpec me(String token) {
        return client.get().uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    /** Token como o servidor emitia antes da V45: mesma assinatura, sem a claim sv. */
    private String legacyToken(String email) {
        return Jwts.builder()
                .issuer(issuer)
                .subject(email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes()))
                .compact();
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
