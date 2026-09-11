package br.com.economize.config;

import br.com.economize.service.ai.AiChatCallerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A chave do servidor nunca chega em branco à autoconfiguração.
 *
 * <p>O caso que derrubou a homologação é o segundo: a variável EXISTE, vazia. O
 * padrão do placeholder não cobre isso — só a ausência.
 */
class AiServerKeyFallbackTest {

    private final AiServerKeyFallback fallback = new AiServerKeyFallback();

    @Test
    @DisplayName("Chave presente e preenchida passa intacta")
    void chavePreenchidaPassaIntacta() {
        MockEnvironment env = new MockEnvironment()
                .withProperty(AiServerKeyFallback.PROPRIEDADE, "AIza-de-verdade");

        fallback.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty(AiServerKeyFallback.PROPRIEDADE)).isEqualTo("AIza-de-verdade");
        assertThat(env.getPropertySources().contains(AiServerKeyFallback.FONTE)).isFalse();
    }

    @Test
    @DisplayName("Variável presente mas VAZIA vira a sentinela — o caso da homologação")
    void chaveVaziaViraSentinela() {
        MockEnvironment env = new MockEnvironment();
        // Como o Render entrega: GEMINI_API_KEY existe com valor ""
        env.getPropertySources().addFirst(new MapPropertySource("painel", Map.of("GEMINI_API_KEY", "")));
        env.getPropertySources().addLast(new MapPropertySource("application.properties",
                Map.of(AiServerKeyFallback.PROPRIEDADE, "${GEMINI_API_KEY:nao-configurada}")));

        // O padrão do placeholder NÃO salva: a variável existe
        assertThat(env.getProperty(AiServerKeyFallback.PROPRIEDADE)).isEmpty();

        fallback.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty(AiServerKeyFallback.PROPRIEDADE))
                .isEqualTo(AiChatCallerFactory.SERVER_KEY_PLACEHOLDER);
    }

    @Test
    @DisplayName("Variável ausente: o padrão do arquivo já resolve, e nada é sobreposto")
    void chaveAusenteFicaComOPadraoDoArquivo() {
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addLast(new MapPropertySource("application.properties",
                Map.of(AiServerKeyFallback.PROPRIEDADE, "${GEMINI_API_KEY:nao-configurada}")));

        fallback.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty(AiServerKeyFallback.PROPRIEDADE))
                .isEqualTo(AiChatCallerFactory.SERVER_KEY_PLACEHOLDER);
        assertThat(env.getPropertySources().contains(AiServerKeyFallback.FONTE)).isFalse();
    }

    @Test
    @DisplayName("Só espaços conta como vazio")
    void soEspacosContaComoVazio() {
        MockEnvironment env = new MockEnvironment()
                .withProperty(AiServerKeyFallback.PROPRIEDADE, "   ");

        fallback.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty(AiServerKeyFallback.PROPRIEDADE))
                .isEqualTo(AiChatCallerFactory.SERVER_KEY_PLACEHOLDER);
    }
}
