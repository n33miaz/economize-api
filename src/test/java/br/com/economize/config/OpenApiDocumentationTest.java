package br.com.economize.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

/**
 * A documentação da API respondendo de verdade (EC-044).
 *
 * <p>Este teste existe por causa de uma falha que <b>nenhum teste de unidade
 * podia pegar</b>: o springdoc era da linha 2.3.0, feita para o Spring Boot 3.2,
 * e chamava um construtor de {@code ControllerAdviceBean} que o Spring 6.2
 * removeu. O {@code NoSuchMethodError} estourava DENTRO do scheduler do Reactor,
 * e requisição que morre ali não devolve 500: fica <b>pendurada para sempre</b>.
 * Em produção isso aparecia como uma página de documentação que nunca carregava.
 *
 * <p>Por isso o teste sobe o contexto inteiro e faz a requisição real, com
 * <b>timeout curto</b> — travar é justamente o modo de falha que se quer pegar,
 * e um teste sem prazo travaria junto.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Documentação da API (EC-044)")
class OpenApiDocumentationTest {

    @Autowired
    private WebTestClient client;

    @Test
    @DisplayName("O /v3/api-docs responde, e responde documento montado")
    void apiDocsResponde() {
        client.mutate().responseTimeout(Duration.ofSeconds(30)).build()
                .get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openapi").exists()
                .jsonPath("$.info.title").isEqualTo("Economize! API")
                // Documento vazio também responde 200: o que prova que o
                // springdoc de fato percorreu os controllers é haver rota
                .jsonPath("$.paths./api/v1/analytics/monthly").exists()
                .jsonPath("$.paths./api/v1/wishes").exists();
    }

    @Test
    @DisplayName("A porta de entrada da documentação não pede token")
    void swaggerUiNaoPedeToken() {
        // `/swagger-ui.html` não casa com `/swagger-ui/**`, que exige a barra:
        // faltando na lista de rotas públicas, a documentação respondia 401
        // antes de qualquer redirecionamento
        client.get().uri("/swagger-ui.html")
                .exchange()
                .expectStatus().value(status -> {
                    if (status != HttpStatus.FOUND.value() && status != HttpStatus.OK.value()) {
                        throw new AssertionError(
                                "esperava redirecionamento ou página, veio " + status);
                    }
                });
    }

    @Test
    @DisplayName("A página do Swagger UI é servida, e não só liberada")
    void paginaDoSwaggerEServida() {
        // Liberar no security e não servir o recurso dá o mesmo resultado
        // prático para quem abre o link: 404 no lugar da documentação
        client.get().uri("/swagger-ui/index.html")
                .exchange()
                .expectStatus().isOk();
    }
}
