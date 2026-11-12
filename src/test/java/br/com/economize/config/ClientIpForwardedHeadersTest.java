package br.com.economize.config;

import br.com.economize.security.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * O IP do cliente com o servidor de verdade tratando cabeçalhos encaminhados,
 * como em produção.
 *
 * <p>Existe por causa de um defeito que teste de unidade nenhum pegava: no
 * Render, o {@code getRemoteAddress()} não é o soquete. Com
 * {@code server.forward-headers-strategy=native} (o padrão do Spring Boot
 * quando detecta plataforma de nuvem) o Reactor Netty o reescreve para a ponta
 * ESQUERDA do {@code X-Forwarded-For} — o texto do cliente. O resolvedor
 * decidia pelo "soquete" e devolvia o valor forjado. Os testes com
 * {@code MockServerHttpRequest} passavam todos, porque lá o endereço remoto é o
 * que o teste escreve.
 *
 * <p>Aqui a requisição passa pelo Netty real, com a propriedade ligada, e as
 * cadeias são as medidas em produção em 12/11/2026. Cada teste confere as duas
 * coisas: que o endereço remoto veio mesmo reescrito (senão o teste não prova
 * nada) e que o IP escolhido é o do cliente.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.forward-headers-strategy=native")
@DisplayName("IP do cliente com cabeçalhos encaminhados em modo nativo")
class ClientIpForwardedHeadersTest {

    private static final String CLIENT = "138.204.213.120";
    private static final String PLATFORM = CLIENT + ", 104.23.254.238, 10.24.210.177";

    @Autowired
    private WebTestClient client;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    @DisplayName("sem cabeçalho do cliente: o endereço remoto é o cliente, e o resolvido também")
    void semForjar() {
        connection(PLATFORM)
                .jsonPath("$.remoteAddress").isEqualTo(CLIENT)
                .jsonPath("$.remoteAddressRewritten").isEqualTo(true)
                .jsonPath("$.forwardedFor[1].kind").isEqualTo("CLOUDFLARE")
                .jsonPath("$.resolvedClientIp").isEqualTo(CLIENT);
    }

    @Test
    @DisplayName("forjado 1.2.3.4: o endereço remoto vira o forjado, o resolvido continua o cliente")
    void forjadoUm() {
        connection("1.2.3.4, " + PLATFORM)
                .jsonPath("$.remoteAddress").isEqualTo("1.2.3.4")
                .jsonPath("$.remoteAddressRewritten").isEqualTo(true)
                .jsonPath("$.resolvedClientIp").isEqualTo(CLIENT);
    }

    @Test
    @DisplayName("forjado 5.6.7.8, 9.9.9.9: o resolvido continua o cliente")
    void forjadoDois() {
        connection("5.6.7.8, 9.9.9.9, " + PLATFORM)
                .jsonPath("$.remoteAddress").isEqualTo("5.6.7.8")
                .jsonPath("$.resolvedClientIp").isEqualTo(CLIENT);
    }

    @Test
    @DisplayName("forjado interno 10.1.1.1: o resolvido continua o cliente")
    void forjadoInterno() {
        connection("10.1.1.1, " + PLATFORM)
                .jsonPath("$.remoteAddress").isEqualTo("10.1.1.1")
                .jsonPath("$.remoteAddressKind").isEqualTo("INTERNAL")
                .jsonPath("$.resolvedClientIp").isEqualTo(CLIENT);
    }

    @Autowired
    private br.com.economize.repository.UserRepository userRepository;

    private WebTestClient.BodyContentSpec connection(String forwardedFor) {
        // Desde a V45 o filtro confere a versão da sessão no banco: token de
        // conta que não existe não autentica mais, então a conta precisa existir
        if (userRepository.findByEmail("teste@economize.app").isEmpty()) {
            userRepository.save(br.com.economize.model.User.builder()
                    .name("Teste").email("teste@economize.app").password("x").build());
        }
        return client.get().uri("/api/v1/users/me/connection")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtUtil.generateToken("teste@economize.app"))
                .header("X-Forwarded-For", forwardedFor)
                .exchange()
                .expectStatus().isOk()
                .expectBody();
    }
}
