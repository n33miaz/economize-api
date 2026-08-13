package br.com.economize.service.statement.category;

import br.com.economize.model.Category;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bônus atrás de feature flag (AI_CATEGORIZATION_ENABLED, default false): sugere
 * categoria só para o que regras e keywords não resolveram. Best-effort — qualquer
 * falha vira log e a transação segue para a revisão manual, nunca quebra o upload.
 * A sugestão entra como SUGGESTED com confiança baixa; jamais confirma sozinha.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "economize.ai.categorization.enabled", havingValue = "true")
public class AiCategorySuggester {

    // Um lote por upload — protege custo e latência mesmo em extratos grandes
    private static final int MAX_DESCRIPTIONS = 40;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiCategorySuggester(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * @return mapa descrição-normalizada → slug de categoria do catálogo; vazio em falha
     */
    public Map<String, String> suggest(List<String> descriptions, List<Category> catalog) {
        if (descriptions.isEmpty() || catalog.isEmpty()) return Map.of();
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

            SystemPromptTemplate template = new SystemPromptTemplate(systemText);
            Message system = template.createMessage(Map.of("catalog", catalogText));
            UserMessage user = new UserMessage("Descrições:\n" + descriptionsText);

            String reply = chatClient.prompt(new Prompt(List.of(system, user))).call().content();
            Map<String, String> parsed = parseJson(reply);

            // valida contra o catálogo — a IA não pode inventar categoria
            var validSlugs = catalog.stream().map(Category::getSlug).collect(Collectors.toSet());
            return parsed.entrySet().stream()
                    .filter(e -> validSlugs.contains(e.getValue()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
        } catch (Exception e) {
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
