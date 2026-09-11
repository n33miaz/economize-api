package br.com.economize;

import br.com.economize.service.ai.AiChatCallerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O incidente da homologação, reproduzido: a chave do servidor chega EM BRANCO
 * (a variável existe no painel, sem valor) e a aplicação precisa subir mesmo
 * assim. O {@code application.properties} de teste dá uma chave; aqui ela é
 * sobreposta por vazio, exatamente como {@code GEMINI_API_KEY=} faz no Render.
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=")
class BootWithoutAiKeyTests {

    @Autowired
    private AiChatCallerFactory factory;

    @Test
    @DisplayName("Sobe com a chave do servidor em branco, e a factory sabe que não tem chave")
    void sobeSemChaveDoServidor() {
        assertThat(factory.serverKeyConfigured()).isFalse();
    }
}
