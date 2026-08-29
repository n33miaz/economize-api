package br.com.economize.service.statement.category;

import br.com.economize.model.Category;
import br.com.economize.model.User;
import br.com.economize.service.ai.AiChatCaller;
import br.com.economize.service.ai.AiChatCallerFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Bônus opcional: sugere categoria só para o que regras e keywords não
 * resolveram. Best-effort — qualquer falha vira log e a transação segue para a
 * revisão manual, nunca quebra o upload. A sugestão entra como SUGGESTED com
 * confiança baixa; jamais confirma sozinha.
 *
 * <p><b>Quando roda (EC-107).</b> Duas portas, e são independentes:
 * <ul>
 *   <li>{@code AI_CATEGORIZATION_ENABLED=true} — a porta de sempre. Aqui quem
 *   paga é o dono do deploy, então a decisão é dele e o default segue false.</li>
 *   <li>O usuário cadastrou chave própria — a chamada sai na chave DELE, para o
 *   provedor que ELE escolheu. O argumento de custo que mantém a flag desligada
 *   não existe nesse caminho, e cadastrar a chave já é o consentimento explícito
 *   de mandar descrições de transação para aquele provedor.</li>
 * </ul>
 *
 * <p>O bean deixou de ser {@code @ConditionalOnProperty} porque a decisão passou
 * a ser POR USUÁRIO e não mais por ambiente. Para quem não se enquadra em nenhuma
 * das duas portas o resultado é idêntico ao de antes: mapa vazio, e o pipeline
 * de importação segue sem nunca ter chamado IA nenhuma.
 */
@Slf4j
@Service
public class AiCategorySuggester {

    // Um lote por upload — protege custo e latência mesmo em extratos grandes
    private static final int MAX_DESCRIPTIONS = 40;

    private final AiChatCallerFactory chatCallerFactory;
    private final boolean serverKeyAllowed;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiCategorySuggester(AiChatCallerFactory chatCallerFactory,
                               @Value("${economize.ai.categorization.enabled:false}") boolean serverKeyAllowed) {
        this.chatCallerFactory = chatCallerFactory;
        this.serverKeyAllowed = serverKeyAllowed;
    }

    /**
     * Alguma das duas portas está aberta para esta conta?
     *
     * <p>Existe para a importação poder DESISTIR CEDO. O bem que ela protege é
     * concreto: montar o catálogo de categorias visíveis custa uma consulta ao
     * banco, e pagá-la para depois descobrir que não há IA nenhuma seria cobrar
     * de todo mundo o preço de um bônus que quase ninguém ligou. Numa instalação
     * padrão (flag desligada e sem cofre) esta pergunta nem chega ao banco — ver
     * o curto-circuito em {@code AiChatCallerFactory.resolve}.
     */
    public boolean appliesTo(User user) {
        try {
            return chatCallerFactory.resolve(user, serverKeyAllowed).isPresent();
        } catch (RuntimeException e) {
            // chave própria ilegível: não há sugestão a dar, e dizer "não se
            // aplica" é o mesmo desfecho de suggest() — o upload segue
            log.warn("Configuração de IA do usuário indisponível; importação segue sem sugestão: {}",
                    e.getMessage());
            return false;
        }
    }

    /**
     * @return mapa descrição-normalizada → slug de categoria do catálogo; vazio
     *         em falha, e vazio também quando não há IA aplicável a este usuário
     */
    public Map<String, String> suggest(User user, List<String> descriptions, List<Category> catalog) {
        if (descriptions.isEmpty() || catalog.isEmpty()) return Map.of();

        Optional<AiChatCaller> resolved;
        try {
            // resolve de novo, e de propósito: este método é entrada pública por
            // si só e não pode depender de o chamador ter passado por appliesTo.
            // O custo é uma leitura indexada por id de usuário, na antessala de
            // uma chamada de LLM que leva segundos
            resolved = chatCallerFactory.resolve(user, serverKeyAllowed);
        } catch (RuntimeException e) {
            // chave própria cadastrada e ilegível: o upload não pode falhar por
            // causa de um bônus — a fila de revisão manual dá conta
            log.warn("Configuração de IA do usuário indisponível; importação segue sem sugestão: {}",
                    e.getMessage());
            return Map.of();
        }
        if (resolved.isEmpty()) return Map.of();
        AiChatCaller caller = resolved.get();

        List<String> batch = descriptions.stream().distinct().limit(MAX_DESCRIPTIONS).toList();

        try {
            String catalogText = catalog.stream()
                    .map(c -> "- " + c.getSlug() + " (" + c.getName() + ")")
                    .collect(Collectors.joining("\n"));
            String descriptionsText = batch.stream()
                    .map(d -> "- " + d)
                    .collect(Collectors.joining("\n"));

            String systemText = """
                    Você classifica descrições de transações bancárias brasileiras em categorias.
                    Categorias disponíveis (use exatamente o slug):
                    {catalog}

                    Responda SOMENTE com um objeto JSON puro, sem markdown e sem comentários,
                    mapeando cada descrição recebida para um slug da lista.
                    Se não tiver confiança razoável em alguma descrição, omita a chave.
                    """;

            String systemPrompt = new SystemPromptTemplate(systemText).render(Map.of("catalog", catalogText));
            String reply = caller.complete(systemPrompt, "Descrições:\n" + descriptionsText);
            Map<String, String> parsed = parseJson(reply);

            // valida contra o catálogo — a IA não pode inventar categoria
            var validSlugs = catalog.stream().map(Category::getSlug).collect(Collectors.toSet());
            return parsed.entrySet().stream()
                    .filter(e -> validSlugs.contains(e.getValue()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
        } catch (Exception e) {
            // só a mensagem, nunca a stack: falha de provedor pode carregar corpo
            // de resposta, e corpo de resposta é onde uma chave ecoada apareceria
            log.warn("Sugestão de categorias via IA falhou; seguindo sem ela: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, String> parseJson(String reply) throws Exception {
        if (reply == null) return Map.of();
        String json = reply.trim();
        // modelos adoram embrulhar em cerca de código mesmo quando pedimos JSON puro
        if (json.startsWith("```")) {
            json = json.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) return Map.of();
        return objectMapper.readValue(json.substring(start, end + 1), new TypeReference<>() {
        });
    }
}
