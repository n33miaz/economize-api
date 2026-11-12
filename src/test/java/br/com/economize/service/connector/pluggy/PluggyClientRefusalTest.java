package br.com.economize.service.connector.pluggy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quando a recusa do pedido de coleta significa "reconecte" e quando é só
 * "agora não". Separado do PluggyClientTest porque aqui não há WebClient a
 * dublar, e os stubs estritos de lá acusariam desperdício.
 */
class PluggyClientRefusalTest {

    private static org.springframework.web.reactive.function.client.WebClientResponseException erro(
            int status, String corpo) {
        return org.springframework.web.reactive.function.client.WebClientResponseException.create(
                status, "x", org.springframework.http.HttpHeaders.EMPTY,
                corpo.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("credencial ou consentimento recusado exige a pessoa; limite e coleta em andamento não")
    void onlyCredentialRefusalNeedsTheUser() {
        assertThat(PluggyClient.exigeAcaoDaPessoa(erro(400, "{\"code\":\"INVALID_CREDENTIALS\"}"))).isTrue();
        assertThat(PluggyClient.exigeAcaoDaPessoa(erro(403, "{}"))).isTrue();
        // 429: limite de pedidos — a conexão está boa, só não agora
        assertThat(PluggyClient.exigeAcaoDaPessoa(erro(429, "{}"))).isFalse();
        // coleta já em andamento chega como 400 comum, separado pelo corpo
        assertThat(PluggyClient.exigeAcaoDaPessoa(
                erro(400, "{\"codeDescription\":\"ITEM_IS_ALREADY_UPDATING\"}"))).isFalse();
        assertThat(PluggyClient.exigeAcaoDaPessoa(erro(409, "{}"))).isFalse();
        assertThat(PluggyClient.exigeAcaoDaPessoa(erro(503, "{}"))).isFalse();
    }
}
