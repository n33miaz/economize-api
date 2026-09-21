package br.com.economize.service.connector.pluggy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * "Tenho credencial para falar com o Pluggy?" — a pergunta que decide se o
 * conector sequer tenta.
 *
 * <p>Vive fora de {@code PluggyClientTest} de propósito: lá o dublê do
 * WebClient é montado para toda a classe, e um teste que não faz requisição
 * nenhuma reprovaria por dublê não usado.
 */
class PluggyClientConfigTest {

    private static final String BASE = "https://api.pluggy.test";

    private PluggyClient com(String id, String segredo) {
        return new PluggyClient(mock(WebClient.class), BASE, id, segredo);
    }

    @Test
    @DisplayName("as duas metades da credencial são obrigatórias")
    void asDuasMetadesSaoObrigatorias() {
        assertThat(com("id", "segredo").isConfigured()).isTrue();
        assertThat(com("", "segredo").isConfigured()).isFalse();
        assertThat(com("id", "").isConfigured()).isFalse();
        // só espaço é o caso que passa despercebido numa variável de ambiente
        // colada torta no painel — e uma chamada com ela responderia 401
        assertThat(com("   ", "   ").isConfigured()).isFalse();
    }
}
