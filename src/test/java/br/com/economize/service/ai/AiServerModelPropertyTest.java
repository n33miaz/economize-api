package br.com.economize.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O modelo da chave do servidor vem do application.properties de PRODUÇÃO.
 *
 * <p>O perfil de teste fixa {@code test-model}, então nenhum teste de contexto
 * enxerga o padrão real — e foi justamente o padrão real (gemini-2.0-flash,
 * aposentado em 12/11/2026) que derrubou o assistente. Aqui a linha é lida do
 * arquivo de main e resolvida como o Spring resolveria.
 */
class AiServerModelPropertyTest {

    private static final String KEY = "spring.ai.openai.chat.options.model";

    private static String rawValue() throws Exception {
        // pelo caminho do arquivo, e não pelo classpath: no classpath de teste o
        // application.properties de test/resources esconde o de main
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return props.getProperty(KEY);
        }
    }

    @Test
    @DisplayName("Sem AI_SERVER_MODEL, o servidor usa o modelo fixo gemini-3.5-flash")
    void defaultsToTheFixedFlashModel() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);

        assertThat(env.resolvePlaceholders(rawValue())).isEqualTo("gemini-3.5-flash");
    }

    @Test
    @DisplayName("AI_SERVER_MODEL fixa outro modelo sem deploy")
    void environmentVariableOverridesTheModel() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("painel",
                Map.of("AI_SERVER_MODEL", "gemini-3.1-flash-lite")));

        assertThat(env.resolvePlaceholders(rawValue())).isEqualTo("gemini-3.1-flash-lite");
    }

    @Test
    @DisplayName("O raciocínio do servidor é 'low' por padrão e muda pelo painel")
    void reasoningEffortDefaultsToLow() throws Exception {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        String raw = props.getProperty("spring.ai.openai.chat.options.reasoning-effort");

        StandardEnvironment semPainel = new StandardEnvironment();
        semPainel.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        semPainel.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        assertThat(semPainel.resolvePlaceholders(raw)).isEqualTo("low");

        StandardEnvironment comPainel = new StandardEnvironment();
        comPainel.getPropertySources().addFirst(new MapPropertySource("painel",
                Map.of("AI_SERVER_REASONING_EFFORT", "medium")));
        assertThat(comPainel.resolvePlaceholders(raw)).isEqualTo("medium");
    }
}
