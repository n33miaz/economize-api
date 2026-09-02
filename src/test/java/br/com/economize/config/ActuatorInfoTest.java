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
                "management.info.java.enabled=true"
        })
@DisplayName("Identidade da build no /actuator/info (EC-044)")
class ActuatorInfoTest {

    @Autowired
    private WebTestClient client;

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
}
