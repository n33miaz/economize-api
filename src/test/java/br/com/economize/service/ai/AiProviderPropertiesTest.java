package br.com.economize.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O catálogo de provedores do EC-107 — e, principalmente, a SOBRESCRITA por
 * ambiente, que é a capacidade em que a decisão de "lista curada" se apoia.
 *
 * <p>A metade de cima usa o bean que o CONTEXTO SPRING construiu, não um
 * {@code new AiProviderProperties()}. Essa distinção é a razão de este arquivo
 * existir: o mecanismo anterior deixava o bean do contexto com {@code models},
 * {@code label} e {@code maxTokens} nulos em todos os provedores — porque o
 * binder de mapas substitui o valor da chave inteira em vez de fundir campo a
 * campo — e a suíte seguia verde, já que todo teste montava a classe na mão. Um
 * teste que só olha o objeto construído à mão prova a intenção do código, nunca
 * o comportamento do sistema.
 */
@SpringBootTest
class AiProviderPropertiesTest {

    @Autowired
    private AiProviderProperties springBean;

    // ------------------------------------------------------------------
    // O bean que o Spring realmente construiu, sob o perfil de teste — que
    // sobrescreve o endpoint dos quatro provedores
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Sobrescrever o endpoint no ambiente NÃO apaga modelos, rótulo nem teto de tokens")
    void springBeanKeepsCuratedCatalogDespiteEndpointOverride() {
        for (AiProvider provider : AiProvider.values()) {
            AiProviderProperties.ProviderConfig config = springBean.get(provider);

            assertThat(config.endpoint())
                    .as("endpoint sobrescrito de %s", provider)
                    .isEqualTo("https://example.test/" + provider.key() + "/chat/completions");
            assertThat(config.models())
                    .as("modelos de %s sobreviveram à sobrescrita do endpoint", provider)
                    .isNotEmpty();
            assertThat(config.label()).as("rótulo de %s", provider).isNotBlank();
            assertThat(config.apiKeyUrl()).as("apiKeyUrl de %s", provider).isNotBlank();
            assertThat(config.defaultModel()).as("modelo padrão de %s", provider).isNotBlank();
        }

        // o teto de tokens da Anthropic é o caso que mais custava: sem ele a
        // camada de compatibilidade recusa a chamada
        assertThat(springBean.get(AiProvider.ANTHROPIC).maxTokens()).isEqualTo(4096);
        assertThat(springBean.get(AiProvider.OPENAI).maxTokens()).isNull();
    }

    @Test
    @DisplayName("O provedor da chave do servidor é validado na subida e sai em forma canônica")
    void springBeanResolvesServerProvider() {
        assertThat(springBean.serverProviderName()).isEqualTo("GEMINI");
    }

    // ------------------------------------------------------------------
    // As receitas escritas no application.properties, uma a uma, exatamente
    // como estão escritas lá
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Receita 1 — extra-models acrescenta à lista curada sem tocar em mais nada")
    void extraModelsAppendsToTheCuratedList() {
        AiProviderProperties.ProviderConfig openai = bound(
                "economize.ai.providers.openai.extra-models", "gpt-4.1-nano").get(AiProvider.OPENAI);

        assertThat(openai.models())
                .containsExactly("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "gpt-4.1-nano");
        // o modelo padrão continua sendo o primeiro da lista curada
        assertThat(openai.defaultModel()).isEqualTo("gpt-4o-mini");
        assertThat(openai.label()).isEqualTo("OpenAI");
        assertThat(openai.endpoint()).isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(openai.apiKeyUrl()).isEqualTo("https://platform.openai.com/api-keys");
    }

    @Test
    @DisplayName("Receita 2 — models substitui a lista inteira, e só ela")
    void modelsReplacesTheWholeList() {
        AiProviderProperties.ProviderConfig openai = bound(
                "economize.ai.providers.openai.models", "gpt-4o-mini,gpt-4.1").get(AiProvider.OPENAI);

        assertThat(openai.models()).containsExactly("gpt-4o-mini", "gpt-4.1");
        assertThat(openai.label()).isEqualTo("OpenAI");
        assertThat(openai.endpoint()).isEqualTo("https://api.openai.com/v1/chat/completions");
    }

    @Test
    @DisplayName("Receita 3 — endpoint trocado, resto do provedor intacto")
    void endpointOverrideKeepsTheRest() {
        AiProviderProperties.ProviderConfig gemini = bound(
                "economize.ai.providers.gemini.endpoint", "https://proxy.interno/chat/completions")
                .get(AiProvider.GEMINI);

        assertThat(gemini.endpoint()).isEqualTo("https://proxy.interno/chat/completions");
        assertThat(gemini.models()).containsExactly("gemini-2.0-flash", "gemini-2.5-flash", "gemini-2.5-pro");
        assertThat(gemini.label()).isEqualTo("Google Gemini");
    }

    @Test
    @DisplayName("Receita 4 — max-tokens trocado, resto do provedor intacto")
    void maxTokensOverrideKeepsTheRest() {
        AiProviderProperties.ProviderConfig anthropic = bound(
                "economize.ai.providers.anthropic.max-tokens", "8192").get(AiProvider.ANTHROPIC);

        assertThat(anthropic.maxTokens()).isEqualTo(8192);
        assertThat(anthropic.models()).contains("claude-haiku-4-5");
        assertThat(anthropic.endpoint()).isEqualTo("https://api.anthropic.com/v1/chat/completions");
    }

    @Test
    @DisplayName("Mexer num provedor não encosta nos outros três")
    void overridingOneProviderLeavesTheOthersAlone() {
        AiProviderProperties properties = bound("economize.ai.providers.gemini.models", "gemini-3-pro");

        assertThat(properties.get(AiProvider.GEMINI).models()).containsExactly("gemini-3-pro");
        assertThat(properties.get(AiProvider.OPENAI).models()).contains("gpt-4o-mini");
        assertThat(properties.get(AiProvider.ANTHROPIC).maxTokens()).isEqualTo(4096);
        assertThat(properties.get(AiProvider.OPENROUTER).label()).isEqualTo("OpenRouter");
    }

    @Test
    @DisplayName("extra-models repetindo um modelo que já existe não o duplica no seletor")
    void extraModelsDoesNotDuplicate() {
        AiProviderProperties.ProviderConfig openai = bound(
                "economize.ai.providers.openai.extra-models", "gpt-4o,gpt-4.1-nano").get(AiProvider.OPENAI);

        assertThat(openai.models()).containsExactlyInAnyOrder(
                "gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "gpt-4.1-nano");
        assertThat(openai.models()).doesNotHaveDuplicates();
    }

    // ------------------------------------------------------------------
    // Configuração torta falha NO BOOT, dizendo provedor e campo
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Provedor desconhecido na chave é erro de boot com a lista aceita junto")
    void unknownProviderKeyFailsWithTheAcceptedList() {
        assertThatThrownBy(() -> bound("economize.ai.providers.llama-caseira.endpoint", "https://x.test/c"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llama-caseira")
                .hasMessageContaining("OPENROUTER");
    }

    @Test
    @DisplayName("O mesmo provedor com duas grafias é ambiguidade, não 'a última vence'")
    void duplicateSpellingOfTheSameProviderFails() {
        assertThatThrownBy(() -> bound(
                "economize.ai.providers.openai.max-tokens", "1000",
                "economize.ai.providers.OPENAI.max-tokens", "2000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mais de uma vez");
    }

    @Test
    @DisplayName("Campo em branco é erro nomeando provedor e campo — nunca vira endpoint nulo")
    void blankFieldFailsNamingProviderAndField() {
        assertThatThrownBy(() -> bound("economize.ai.providers.gemini.endpoint", "  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("economize.ai.providers.gemini.endpoint")
                .hasMessageContaining("branco");
    }

    @Test
    @DisplayName("Endpoint relativo é recusado no boot em vez de virar erro de transporte estranho")
    void relativeEndpointFailsAtStartup() {
        assertThatThrownBy(() -> bound("economize.ai.providers.openai.endpoint", "/v1/chat/completions"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("economize.ai.providers.openai.endpoint")
                .hasMessageContaining("absoluta");
    }

    @Test
    @DisplayName("Lista de modelos vazia é erro, e o erro ensina qual property era a certa")
    void emptyModelsListPointsToExtraModels() {
        assertThatThrownBy(() -> bound("economize.ai.providers.openai.models", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("economize.ai.providers.openai.models")
                .hasMessageContaining("extra-models");
    }

    @Test
    @DisplayName("max-tokens não positivo é erro de boot")
    void nonPositiveMaxTokensFails() {
        assertThatThrownBy(() -> bound("economize.ai.providers.anthropic.max-tokens", "0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("economize.ai.providers.anthropic.max-tokens");
    }

    @Test
    @DisplayName("A forma indexada esparsa (models[4]) é recusada pelo binder — por isso a receita é extra-models")
    void sparseIndexedModelsIsRefused() {
        // documenta a armadilha que motivou o desenho atual: o binder indexado
        // exige os índices contíguos a partir de zero, então models[4] sozinho
        // derruba a subida — foi essa a receita que o projeto já publicou errada
        assertThatThrownBy(() -> bound("economize.ai.providers.openai.models[4]", "gpt-4.1-nano"))
                .isInstanceOf(BindException.class);
    }

    @Test
    @DisplayName("server-provider inválido derruba o boot em vez de virar provider que o app não conhece")
    void invalidServerProviderFailsAtStartup() {
        assertThatThrownBy(() -> bound("economize.ai.server-provider", "gemeni"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("economize.ai.server-provider")
                .hasMessageContaining("gemeni");
    }

    @Test
    @DisplayName("server-provider aceita caixa livre e sai canônico para o app")
    void serverProviderIsNormalized() {
        assertThat(bound("economize.ai.server-provider", " openai ").serverProviderName()).isEqualTo("OPENAI");
    }

    @Test
    @DisplayName("A lista resolvida é imutável: ninguém corrompe o catálogo em runtime")
    void resolvedModelsAreImmutable() {
        AiProviderProperties.ProviderConfig openai = bound().get(AiProvider.OPENAI);

        assertThatThrownBy(() -> openai.models().add("modelo-pirata"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Sem sobrescrita nenhuma, valem os defaults de código e as durações padrão")
    void defaultsStandOnTheirOwn() {
        AiProviderProperties properties = bound();

        assertThat(properties.get(AiProvider.GEMINI).endpoint())
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions");
        assertThat(properties.getMaxKeyLength()).isEqualTo(512);
        assertThat(properties.getTimeout().toSeconds()).isEqualTo(60);
        assertThat(properties.getTestTimeout().toSeconds()).isEqualTo(20);
    }

    /**
     * Binda pelo mesmo caminho que o Spring Boot usa em produção e resolve na
     * hora, para que erro de configuração apareça aqui como apareceria no boot.
     */
    private static AiProviderProperties bound(String... keyValuePairs) {
        Map<String, Object> source = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            source.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        AiProviderProperties target = new AiProviderProperties();
        new Binder(new MapConfigurationPropertySource(source))
                .bind("economize.ai", Bindable.ofInstance(target));
        target.initialize();
        return target;
    }
}
