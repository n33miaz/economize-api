package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.mockito.Mockito.verifyNoInteractions;

/**
 * O contrato da carteira (EC-044).
 *
 * <p>As três rotas não tinham validação nenhuma: corpo vazio chegava ao serviço
 * e só quebrava no banco — 500 no lugar de 400 —, e um {@code type} inventado
 * era aceito, apesar de ser ele quem decide o sinal da posição.
 */
@WebFluxTest(WalletController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class })
@DisplayName("WalletController — o corpo é conferido antes do serviço (EC-044)")
class WalletControllerTest {

    private static final String EMAIL = "teste@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private WalletService walletService;

    private WebTestClient.ResponseSpec post(Map<String, Object> corpo) {
        return webTestClient.post().uri("/api/v1/wallet/transactions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtUtil.generateToken(EMAIL))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(corpo)
                .exchange();
    }

    @Test
    @DisplayName("Corpo vazio é 400, e o serviço nem é chamado")
    void corpoVazioE400() {
        post(Map.of()).expectStatus().isBadRequest();

        // O ponto da validação é este: a operação inválida morre na borda, e
        // não no banco, onde viraria erro interno
        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("Tipo fora de BUY/SELL é recusado")
    void tipoInvalidoERecusado() {
        post(Map.of("assetCode", "PETR4", "type", "BANANA",
                "quantity", 100, "priceAtTransaction", 38.42))
                .expectStatus().isBadRequest();

        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("Quantidade zero não é operação")
    void quantidadeZeroERecusada() {
        post(Map.of("assetCode", "PETR4", "type", "BUY",
                "quantity", 0, "priceAtTransaction", 38.42))
                .expectStatus().isBadRequest();

        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("Preço negativo não é preço")
    void precoNegativoERecusado() {
        post(Map.of("assetCode", "PETR4", "type", "BUY",
                "quantity", 10, "priceAtTransaction", -1))
                .expectStatus().isBadRequest();

        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("Código de ativo em branco é recusado")
    void ativoEmBrancoERecusado() {
        post(Map.of("assetCode", "   ", "type", "BUY",
                "quantity", 10, "priceAtTransaction", 38.42))
                .expectStatus().isBadRequest();

        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("Sem token, nenhuma rota da carteira responde")
    void semTokenNaoResponde() {
        webTestClient.get().uri("/api/v1/wallet/transactions")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
