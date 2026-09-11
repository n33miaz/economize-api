package br.com.economize.config;

import br.com.economize.service.ai.AiChatCallerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Garante que {@code spring.ai.openai.api-key} nunca chegue EM BRANCO à
 * autoconfiguração do Spring AI.
 *
 * <p><b>O incidente.</b> A homologação ficou fora do ar de 08 a 11/09 com
 * {@code OpenAI API key must be set}. A primeira correção pôs um padrão na
 * propriedade ({@code ${GEMINI_API_KEY:nao-configurada}}) — e o deploy seguinte
 * caiu com a MESMA mensagem. O padrão do placeholder só vale quando a variável
 * <i>não existe</i>; no painel do serviço ela existia, vazia. Para o Spring,
 * {@code GEMINI_API_KEY=} é uma chave presente com valor {@code ""}, e
 * {@code Assert.hasText("")} derruba o boot igual.
 *
 * <p><b>O que isto faz.</b> Depois de todas as fontes carregadas (arquivo,
 * variável de ambiente, propriedade de sistema, o que o teste injetar), lê o
 * valor RESOLVIDO da propriedade. Se estiver em branco, sobrepõe a sentinela
 * que o {@link AiChatCallerFactory} já entende como "sem chave do servidor".
 * A aplicação sobe, ninguém chama o provedor com a sentinela, e o assistente
 * avisa que só funciona com chave própria.
 *
 * <p>Roda por último de propósito: precisa ver a propriedade como ela vai
 * chegar ao bean, não como está num arquivo isolado.
 */
public class AiServerKeyFallback implements EnvironmentPostProcessor, Ordered {

    static final String PROPRIEDADE = "spring.ai.openai.api-key";
    static final String FONTE = "economize-chave-ia-sentinela";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String atual = environment.getProperty(PROPRIEDADE);
        if (atual != null && !atual.isBlank()) return;

        environment.getPropertySources().addFirst(new MapPropertySource(FONTE,
                Map.of(PROPRIEDADE, AiChatCallerFactory.SERVER_KEY_PLACEHOLDER)));
        // Logging ainda não subiu neste ponto; stdout é o que o painel mostra
        System.out.println("[ia] chave do servidor ausente ou em branco: o assistente só atende chave própria");
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
