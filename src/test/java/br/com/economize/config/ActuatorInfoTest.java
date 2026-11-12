package br.com.economize.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Que versão está no ar (EC-044).
 *
 * <p>O {@code /actuator/info} respondia <b>{@code {}}</b>: a versão era escrita
 * à mão no {@code application.properties} — e anunciava 2.0.0 contra um build
 * 1.0.0-SNAPSHOT — mas o contribuidor de ambiente do Actuator vem desligado por
 * padrão desde o Boot 2.6, então nem a versão errada aparecia.
 *
 * <p>Agora a identidade vem do {@code build-info} gerado pelo plugin, que não
 * tem como divergir do pom. A mesma anotação de contexto do
 * {@link OpenApiDocumentationTest} de propósito: o Spring reaproveita o contexto
 * já montado em vez de subir outro.
 */
// O application.properties de teste SUBSTITUI o principal, então a exposição
// do endpoint precisa ser declarada aqui: sem ela o Actuator publica só o
// /health e a rota responde 404. O que este teste prova é o contribuidor de
// build entregando a identidade certa — a lista de endpoints expostos é
// configuração de produção e nenhum teste com properties próprias a alcança
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.endpoints.web.exposure.include=health,info,metrics",
                "management.info.build.enabled=true",
                // os mesmos valores de produção — a guarda no fim do arquivo
                // cobra que o application.properties principal diga o mesmo
                "management.info.java.enabled=false",
                "management.endpoint.health.show-details=when-authorized"
        })
@DisplayName("Identidade da build no /actuator/info (EC-044)")
class ActuatorInfoTest {

    @Autowired
    private WebTestClient client;

    @Autowired
    private br.com.economize.security.JwtUtil jwtUtil;

    @Autowired
    private br.com.economize.repository.UserRepository userRepository;

    private String token() {
        // Desde a V45 o filtro confere a versão da sessão no banco: token de
        // conta que não existe não autentica mais, então a conta precisa existir
        if (userRepository.findByEmail("teste@economize.app").isEmpty()) {
            userRepository.save(br.com.economize.model.User.builder()
                    .name("Teste").email("teste@economize.app").password("x").build());
        }
        return "Bearer " + jwtUtil.generateToken("teste@economize.app");
    }

    @Test
    @DisplayName("O /info diz a versão da build, e não um objeto vazio")
    void infoDizAVersaoDaBuild() {
        client.get().uri("/actuator/info")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.build.version").exists()
                .jsonPath("$.build.artifact").isEqualTo("economize-api")
                // A hora da build é o que distingue dois deploys da MESMA
                // versão, que é o caso normal de um SNAPSHOT em produção
                .jsonPath("$.build.time").exists();
    }

    @Test
    @DisplayName("Sem token a saúde responde, mas sem o detalhe dos componentes")
    void saudeSemTokenNaoDetalha() {
        // Só o corpo: no ambiente de teste algum indicador pode estar DOWN
        // (503), e o que se prova aqui é que a resposta não abre os componentes
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().value(status ->
                        org.assertj.core.api.Assertions.assertThat(status).isIn(200, 503))
                .expectBody()
                .jsonPath("$.status").exists()
                .jsonPath("$.components").doesNotExist();
    }

    @Test
    @DisplayName("As métricas da JVM pedem token")
    void metricasPedemToken() {
        client.get().uri("/actuator/metrics").exchange().expectStatus().isUnauthorized();
        client.get().uri("/actuator/metrics/jvm.memory.used").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Com token, a saúde detalha e as métricas respondem")
    void comTokenDetalhaEMede() {
        client.get().uri("/actuator/health")
                .header(org.springframework.http.HttpHeaders.AUTHORIZATION, token())
                .exchange()
                .expectBody()
                .jsonPath("$.components").exists();
        client.get().uri("/actuator/metrics/jvm.memory.used")
                .header(org.springframework.http.HttpHeaders.AUTHORIZATION, token())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("O /info não diz a versão da JVM")
    void infoNaoDizAJvm() {
        client.get().uri("/actuator/info")
                .exchange()
                .expectBody()
                .jsonPath("$.java").doesNotExist();
    }

    @Test
    @DisplayName("A configuração de PRODUÇÃO é a que este teste exercita")
    void producaoUsaOsMesmosValores() throws java.io.IOException {
        // O properties de teste substitui o principal: sem esta leitura direta,
        // alguém reabre o detalhe em produção e os testes acima seguem verdes
        java.util.Properties producao = new java.util.Properties();
        try (var in = java.nio.file.Files.newInputStream(
                java.nio.file.Path.of("src/main/resources/application.properties"))) {
            producao.load(in);
        }
        org.assertj.core.api.Assertions.assertThat(
                producao.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("when-authorized");
        org.assertj.core.api.Assertions.assertThat(
                producao.getProperty("management.info.java.enabled")).isEqualTo("false");
    }
}
