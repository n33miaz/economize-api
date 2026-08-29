package br.com.economize.service.ai;

import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Catálogo de provedores e modelos do EC-107: defaults em código, sobrescritos
 * campo a campo por variável de ambiente.
 *
 * <p><b>Por que lista curada, e não modelo em texto livre nem lista buscada do
 * provedor.</b> As três opções foram consideradas:
 * <ul>
 *   <li><b>Texto livre</b> custa zero de manutenção e entrega um erro opaco: um
 *   "gpt-4o-minii" digitado errado só aparece como 404 do provedor, na hora do
 *   chat, sem o app ter como montar um seletor.</li>
 *   <li><b>Buscar do provedor</b> resolve o frescor e cria um ovo-e-galinha —
 *   listar modelos exige uma chave válida, que é justamente o que o usuário
 *   ainda está cadastrando — além de quatro integrações novas, quatro formatos
 *   de resposta e um cache para não pagar rede a cada abertura de tela.</li>
 *   <li><b>Lista curada sobrescrevível</b> (o que está aqui) dá ao app um seletor
 *   pronto e offline, transforma modelo desconhecido em 400 honesto com a lista
 *   junto, e mantém o custo de acompanhar o mercado no nível de VARIÁVEL DE
 *   AMBIENTE: acrescentar um modelo lançado ontem é uma linha no painel e um
 *   restart, não abrir PR.</li>
 * </ul>
 * Os ids de modelo são perecíveis por natureza — é exatamente por isso que a
 * lista não está compilada dentro de um enum.
 *
 * <p><b>Por que a sobrescrita é feita À MÃO, e não deixando o binder do Spring
 * escrever direto no catálogo.</b> Esta classe já tentou o caminho óbvio —
 * {@code Map<String, ProviderConfig>} pré-preenchido com os defaults — e ele
 * está QUEBRADO de um jeito silencioso. O {@code MapBinder} não funde o valor de
 * um mapa campo a campo: ele constrói um objeto NOVO com o que veio da property
 * e o coloca por cima da chave inteira. Na prática, um inocente
 * {@code economize.ai.providers.gemini.endpoint=...} zerava {@code models},
 * {@code label}, {@code apiKeyUrl} e {@code maxTokens} do Gemini, e a aplicação
 * subia assim; o estouro só aparecia depois, como NullPointerException em cima
 * de {@code models} na primeira vez que alguém abrisse a tela de IA. Pior ainda,
 * a receita antiga de "acrescentar um modelo" ({@code models[4]=...}) derrubava o
 * boot com {@code UnboundConfigurationPropertiesException}, porque o binder
 * indexado exige os índices contíguos a partir de zero.
 *
 * <p>Por isso o que o binder preenche agora é {@link ProviderOverride} — uma
 * estrutura CRUA, de campos todos nulos, num mapa que começa VAZIO. Aí a
 * semântica de "substituir a chave inteira" do binder é justamente o que se quer,
 * porque não há nada embaixo para destruir. A fusão sobre os defaults acontece em
 * {@link #initialize()}, campo a campo, e o resultado é validado antes de virar
 * {@link ProviderConfig} — que é um record imutável e se recusa a existir sem
 * endpoint ou sem modelo. Configuração torta falha NO BOOT, dizendo qual provedor
 * e qual campo.
 *
 * <p><b>Receitas que funcionam</b> (e que estão testadas em
 * {@code AiProviderPropertiesTest}, uma a uma, exatamente como escritas):
 * <pre>
 *   economize.ai.providers.openai.extra-models=gpt-4.1-nano
 *   economize.ai.providers.openai.models=gpt-4o-mini,gpt-4.1
 *   economize.ai.providers.gemini.endpoint=https://.../chat/completions
 *   economize.ai.providers.anthropic.max-tokens=8192
 * </pre>
 * {@code extra-models} ACRESCENTA ao fim da lista curada e é o caminho para o
 * caso comum (saiu modelo novo); {@code models} SUBSTITUI a lista inteira e é
 * para quando se quer restringir o que a instalação oferece. Nenhum dos dois
 * mexe nos outros campos do provedor.
 *
 * <p><b>Sobre os endpoints.</b> Todos apontam para o caminho compatível com a
 * API da OpenAI. No caso da Anthropic isso é uma CAMADA DE COMPATIBILIDADE, não
 * a API nativa: ela cobre o que este produto usa (uma pergunta, uma resposta em
 * texto, sem ferramentas nem streaming), e a alternativa seria trazer o starter
 * {@code spring-ai-anthropic} só por causa dela. A Anthropic posiciona essa
 * camada como auxílio de migração, não como superfície de produção, então ela
 * pode mudar sem aviso — e o plano de conserto continua sendo local e barato:
 * trocar a URL (por property, sem deploy) e, se o formato do corpo também mudar,
 * ensinar {@link OpenAiCompatibleChatClient} a falar o nativo. Nenhum dos outros
 * três provedores é afetado por esse conserto.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "economize.ai")
public class AiProviderProperties {

    /**
     * Teto de caracteres da chave aceita no cadastro. Existe para o envelope
     * cifrado caber no VARCHAR(1024) da V17 e para nenhum corpo absurdo chegar
     * ao cofre.
     */
    private int maxKeyLength = 512;

    /** Tempo máximo esperando o provedor. LLM é lento; os 5 s do WebClient padrão cortariam quase tudo. */
    private Duration timeout = Duration.ofSeconds(60);

    /** Timeout curto do teste de chave: aqui só interessa "respondeu ou não". */
    private Duration testTimeout = Duration.ofSeconds(20);

    /**
     * Provedor que a chave do servidor atende. É SÓ ROTULAGEM — o que manda no
     * caminho do servidor continua sendo {@code spring.ai.openai.*}. Validado na
     * subida contra o enum: um "gemeni" digitado no painel viraria um
     * {@code provider} que o app não reconhece na resposta do GET, e descobrir
     * isso pela tela quebrada é caro demais para um erro de digitação.
     */
    private String serverProvider = AiProvider.GEMINI.name();

    /**
     * Sobrescritas cruas vindas do ambiente. COMEÇA VAZIO de propósito: é o que
     * torna inofensiva a semântica de "substituir a chave inteira" do binder de
     * mapas (ver o javadoc da classe). Os defaults não moram aqui.
     */
    private final Map<String, ProviderOverride> providers = new LinkedHashMap<>();

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private volatile Map<AiProvider, ProviderConfig> resolved;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private volatile AiProvider resolvedServerProvider;

    /**
     * Funde as sobrescritas sobre os defaults e valida tudo. Roda no boot de
     * propósito: erro de configuração tem que aparecer na subida, com o nome do
     * provedor e do campo, e não como NullPointerException seis telas adiante.
     */
    @PostConstruct
    void initialize() {
        Map<AiProvider, ProviderConfig> catalog = resolveCatalog();
        AiProvider server = AiProvider.parse(serverProvider).orElseThrow(() -> new IllegalStateException(
                "economize.ai.server-provider=\"" + serverProvider + "\": provedor desconhecido. Aceitos: "
                        + accepted() + ". É só o rótulo que o GET /api/v1/ai/settings mostra "
                        + "para quem usa a chave do servidor."));
        this.resolved = catalog;
        this.resolvedServerProvider = server;
    }

    /** Configuração já fundida e validada do provedor. Nunca devolve nulo. */
    public ProviderConfig get(AiProvider provider) {
        ProviderConfig config = catalog().get(provider);
        if (config == null) {
            throw new IllegalStateException("Provedor sem configuração: " + provider.key());
        }
        return config;
    }

    /** Nome canônico do provedor da chave do servidor, já validado. */
    public String serverProviderName() {
        catalog();
        return resolvedServerProvider.name();
    }

    /**
     * O Spring chama {@link #initialize()} depois de bindar. Fora do contexto
     * (teste de unidade que constrói a classe na mão) a fusão acontece no
     * primeiro acesso, para que a instância recém-construída já valha os
     * defaults em vez de estourar.
     */
    private Map<AiProvider, ProviderConfig> catalog() {
        Map<AiProvider, ProviderConfig> snapshot = resolved;
        if (snapshot == null) {
            initialize();
            snapshot = resolved;
        }
        return snapshot;
    }

    private Map<AiProvider, ProviderConfig> resolveCatalog() {
        Map<AiProvider, ProviderOverride> overrides = new EnumMap<>(AiProvider.class);
        for (Map.Entry<String, ProviderOverride> entry : providers.entrySet()) {
            AiProvider provider = AiProvider.parse(entry.getKey())
                    .orElseThrow(() -> new IllegalStateException("economize.ai.providers." + entry.getKey()
                            + ": provedor desconhecido. Aceitos: " + accepted()));
            if (overrides.put(provider, entry.getValue()) != null) {
                // duas grafias da mesma chave e a última venceria por acaso —
                // configuração ambígua não pode virar "depende da ordem do parse"
                throw new IllegalStateException("economize.ai.providers." + provider.key()
                        + ": declarado mais de uma vez, com grafias diferentes");
            }
        }

        Map<AiProvider, ProviderConfig> catalog = new EnumMap<>(AiProvider.class);
        defaults().forEach((provider, base) -> catalog.put(provider, merge(provider, base, overrides.get(provider))));
        return catalog;
    }

    private ProviderConfig merge(AiProvider provider, ProviderConfig base, ProviderOverride over) {
        if (over == null) {
            return base;
        }
        List<String> models = base.models();
        if (over.getModels() != null) {
            models = cleanModels(provider, "models", over.getModels());
        }
        if (over.getExtraModels() != null) {
            List<String> appended = new ArrayList<>(models);
            appended.addAll(cleanModels(provider, "extra-models", over.getExtraModels()));
            models = appended;
        }

        Integer maxTokens = base.maxTokens();
        if (over.getMaxTokens() != null) {
            if (over.getMaxTokens() <= 0) {
                throw new IllegalStateException(field(provider, "max-tokens")
                        + ": precisa ser maior que zero");
            }
            maxTokens = over.getMaxTokens();
        }

        ProviderConfig merged = new ProviderConfig(
                text(provider, "label", over.getLabel(), base.label()),
                text(provider, "endpoint", over.getEndpoint(), base.endpoint()),
                text(provider, "api-key-url", over.getApiKeyUrl(), base.apiKeyUrl()),
                dedupe(models),
                maxTokens);
        requireAbsoluteUrl(provider, merged.endpoint());
        return merged;
    }

    private String text(AiProvider provider, String name, String override, String fallback) {
        if (override == null) {
            return fallback;
        }
        String value = override.trim();
        if (value.isEmpty()) {
            // apagar um campo nunca é o que se quis dizer: remover a linha é que
            // devolve o default, e uma string vazia aqui viraria endpoint nulo
            throw new IllegalStateException(field(provider, name)
                    + ": valor em branco — remova a linha para voltar ao padrão");
        }
        return value;
    }

    private List<String> cleanModels(AiProvider provider, String name, List<String> raw) {
        List<String> cleaned = raw.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                // vírgula sobrando no fim da linha é o erro de digitação mais
                // comum da lista e não merece derrubar deploy
                .filter(model -> !model.isEmpty())
                .toList();
        if (cleaned.isEmpty()) {
            throw new IllegalStateException(field(provider, name) + ": lista vazia — para ACRESCENTAR "
                    + "um modelo à lista padrão use extra-models; models substitui a lista inteira");
        }
        return cleaned;
    }

    private void requireAbsoluteUrl(AiProvider provider, String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(field(provider, "endpoint") + ": não é uma URL válida");
        }
        if (!uri.isAbsolute() || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            // o cliente de IA usa a URL inteira, sem base nem template por cima:
            // endpoint relativo viraria erro de transporte estranho em runtime
            throw new IllegalStateException(field(provider, "endpoint")
                    + ": precisa ser uma URL absoluta http(s), com o caminho completo do chat completions");
        }
    }

    private static List<String> dedupe(List<String> models) {
        return List.copyOf(new LinkedHashSet<>(models));
    }

    private static String field(AiProvider provider, String name) {
        return "economize.ai.providers." + provider.key() + "." + name;
    }

    private static String accepted() {
        return Arrays.stream(AiProvider.values()).map(Enum::name).collect(Collectors.joining(", "));
    }

    /**
     * O que o ambiente pode sobrescrever. Todos os campos NULOS por padrão: nulo
     * significa "não mexeu", e só o que não é nulo entra por cima do default.
     */
    @Data
    public static class ProviderOverride {
        private String label;
        private String endpoint;
        private String apiKeyUrl;

        /** Substitui a lista curada inteira. */
        private List<String> models;

        /** Acrescenta ao fim da lista curada — o caminho para "saiu modelo novo". */
        private List<String> extraModels;

        private Integer maxTokens;
    }

    /**
     * Configuração RESOLVIDA de um provedor: defaults já fundidos com o
     * ambiente. Record imutável e com guarda no construtor — é a garantia
     * estrutural de que nenhum provedor sem endpoint ou sem modelo chega ao
     * runtime, mesmo que alguém acrescente um caminho de construção novo depois.
     */
    public record ProviderConfig(String label, String endpoint, String apiKeyUrl,
                                 List<String> models, Integer maxTokens) {

        public ProviderConfig {
            Objects.requireNonNull(label, "label do provedor");
            Objects.requireNonNull(endpoint, "endpoint do provedor");
            Objects.requireNonNull(apiKeyUrl, "apiKeyUrl do provedor");
            models = List.copyOf(Objects.requireNonNull(models, "models do provedor"));
            if (models.isEmpty()) {
                throw new IllegalArgumentException("provedor sem nenhum modelo declarado");
            }
        }

        /** O primeiro da lista é o sugerido quando o usuário não escolhe. */
        public String defaultModel() {
            return models.get(0);
        }
    }

    private static Map<AiProvider, ProviderConfig> defaults() {
        Map<AiProvider, ProviderConfig> map = new EnumMap<>(AiProvider.class);
        map.put(AiProvider.GEMINI, new ProviderConfig("Google Gemini",
                "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                "https://aistudio.google.com/apikey",
                List.of("gemini-2.0-flash", "gemini-2.5-flash", "gemini-2.5-pro"),
                null));
        map.put(AiProvider.OPENAI, new ProviderConfig("OpenAI",
                "https://api.openai.com/v1/chat/completions",
                "https://platform.openai.com/api-keys",
                List.of("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1"),
                null));
        map.put(AiProvider.ANTHROPIC, new ProviderConfig("Anthropic Claude",
                "https://api.anthropic.com/v1/chat/completions",
                "https://console.anthropic.com/settings/keys",
                List.of("claude-haiku-4-5", "claude-sonnet-4-5", "claude-opus-4-1"),
                // só a Anthropic precisa: a API nativa dela exige max_tokens e a
                // camada de compatibilidade herda a exigência. Nos outros o
                // parâmetro é opcional e alguns modelos de raciocínio recentes
                // RECUSAM justamente este nome (querem max_completion_tokens) —
                // mandar por via das dúvidas quebraria modelo que hoje funciona
                4096));
        map.put(AiProvider.OPENROUTER, new ProviderConfig("OpenRouter",
                "https://openrouter.ai/api/v1/chat/completions",
                "https://openrouter.ai/settings/keys",
                List.of("openai/gpt-4o-mini", "anthropic/claude-sonnet-4.5",
                        "google/gemini-2.5-flash", "deepseek/deepseek-chat"),
                null));
        return map;
    }
}
