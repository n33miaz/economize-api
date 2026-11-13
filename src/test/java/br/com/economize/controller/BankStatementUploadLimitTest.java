package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.config.StatementImportProperties;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.BankStatementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.ReactiveMultipartAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O teto do upload na camada do multipart: o arquivo grande é cortado enquanto
 * é gravado, e o serviço (e portanto o parser) nem chega a ser chamado.
 *
 * <p>Tetos minúsculos de propósito, para não alocar megabytes no teste. O
 * {@code max-in-memory-size} também é reduzido: o limite de disco só age
 * depois que a parte transborda da memória, e com o padrão de 256 KB um
 * arquivo de 8 KB passaria sem nunca tocar no teto.
 */
@WebFluxTest(BankStatementController.class)
@ImportAutoConfiguration(ReactiveMultipartAutoConfiguration.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class,
        StatementImportProperties.class })
@TestPropertySource(properties = {
        "spring.webflux.multipart.max-in-memory-size=1KB",
        "spring.webflux.multipart.max-disk-usage-per-part=4KB",
        "economize.statement.max-upload-size=4KB" })
class BankStatementUploadLimitTest {

    private static final String EMAIL = "teste@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private BankStatementService bankStatementService;

    @Test
    @DisplayName("Arquivo acima do teto responde 413 em pt-BR, sem chamar o serviço")
    void acimaDoTetoE413() {
        enviar(8 * 1024)
                .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Arquivo Muito Grande")
                .jsonPath("$.detail").value(detail -> org.assertj.core.api.Assertions
                        .assertThat((String) detail).contains("limite de 4 KB"));

        verify(bankStatementService, never()).processFile(any(), any(), any());
    }

    @Test
    @DisplayName("Arquivo abaixo do teto segue para o serviço normalmente")
    void abaixoDoTetoPassa() {
        when(bankStatementService.processFile(eq(EMAIL), any(), isNull()))
                .thenReturn(Mono.just(new BankStatementService.ImportResult(
                        UUID.randomUUID(), 1, 0, 0, 0, false, "OFX")));

        enviar(3 * 1024).expectStatus().isOk();

        verify(bankStatementService).processFile(eq(EMAIL), any(), isNull());
    }

    private WebTestClient.ResponseSpec enviar(int tamanho) {
        byte[] conteudo = new byte[tamanho];
        Arrays.fill(conteudo, (byte) 'A');
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(conteudo) {
            @Override
            public String getFilename() {
                return "extrato.ofx";
            }
        });
        return webTestClient.post().uri("/api/v1/bank-statements/upload")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtUtil.generateToken(EMAIL))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .exchange();
    }
}
