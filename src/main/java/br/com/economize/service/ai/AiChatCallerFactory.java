package br.com.economize.service.ai;

import br.com.economize.dto.ai.ChatTurn;
import br.com.economize.model.User;
import br.com.economize.model.UserAiSettings;
import br.com.economize.repository.UserAiSettingsRepository;
import br.com.economize.security.SecretCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decide COM QUAL chave e para QUAL provedor cada chamada de IA sai — EC-107.
 *
 * <p>A regra tem três casos e nenhum deles é silencioso:
 * <ol>
 *   <li><b>O usuário cadastrou chave própria</b> → a chamada sai nela, para o
 *   provedor e o modelo que ele escolheu.</li>
 *   <li><b>O usuário não cadastrou nada</b> → cai na chave do servidor, pelo
 *   caminho do Spring AI, <b>exatamente como antes deste ticket</b>. É o mesmo
 *   {@code ChatClient} autoconfigurado de sempre; nada foi reimplementado
 *   embaixo de quem não pediu mudança nenhuma.</li>
 *   <li><b>O usuário cadastrou e a chave não pode ser lida</b> (chave-mestra
 *   trocada ou ausente) → <b>erro honesto</b>. Não cai na chave do servidor: ele
 *   escolheu para onde os dados financeiros dele deveriam ir, e trocar esse
 *   destino em silêncio — mandando o extrato para o provedor do dono do deploy e
 *   gastando o dinheiro dele — seria pior do que falhar dizendo o que houve.</li>
 * </ol>
 */
@Slf4j
@Service
public class AiChatCallerFactory {

    private final UserAiSettingsRepository repository;
    private final SecretCipher cipher;
    private final AiProviderProperties properties;
    private final OpenAiCompatibleChatClient httpClient;
    private final ChatClient serverChatClient;
    private final String serverModel;
    private final boolean serverKeyConfigured;

    /**
     * O valor que {@code spring.ai.openai.api-key} assume quando a variável de
     * ambiente não existe. Não é segredo, é sentinela: a autoconfiguração do
     * Spring AI recusa chave vazia e derrubava o boot de um ambiente sem
     * {@code GEMINI_API_KEY} — foi o que manteve a homologação fora do ar. Com
     * a sentinela o contexto sobe, e esta classe garante que ninguém chama o
     * provedor com ela.
     */
    public static final String SERVER_KEY_PLACEHOLDER = "nao-configurada";

    public AiChatCallerFactory(UserAiSettingsRepository repository,
                               SecretCipher cipher,
                               AiProviderProperties properties,
                               OpenAiCompatibleChatClient httpClient,
                               ChatClient.Builder serverChatClientBuilder,
                               @Value("${spring.ai.openai.chat.options.model:}") String serverModel,
                               @Value("${spring.ai.openai.api-key:}") String serverApiKey) {
        this.repository = repository;
        this.cipher = cipher;
        this.properties = properties;
        this.httpClient = httpClient;
        this.serverChatClient = serverChatClientBuilder.build();
        this.serverModel = serverModel;
        this.serverKeyConfigured = serverApiKey != null
                && !serverApiKey.isBlank()
                && !SERVER_KEY_PLACEHOLDER.equals(serverApiKey.trim());
        if (!serverKeyConfigured) {
            log.warn("Sem chave de IA do servidor: o assistente só responde a quem cadastrar a própria chave");
        }
    }

    /** O deploy tem chave própria para o provedor? Sem ela, só o caminho BYOK existe. */
    public boolean serverKeyConfigured() {
        return serverKeyConfigured;
    }

    /**
     * @param serverFallbackAllowed o chamador aceita rodar na chave do servidor?
     *                              O assistente aceita (é o comportamento de
     *                              hoje); a categorização só aceita quando a
     *                              flag {@code economize.ai.categorization.enabled}
     *                              está ligada, porque ali quem paga é o dono do
     *                              deploy e a decisão é dele
     * @return vazio quando não há IA aplicável para este usuário
     * @throws IllegalArgumentException a configuração existe e está ilegível — o
     *                                  usuário precisa recadastrar (vira 400)
     */
    public Optional<AiChatCaller> resolve(User user, boolean serverFallbackAllowed) {
        // Antes de qualquer consulta: numa instalação sem cofre, nenhuma chave
        // própria é legível, então quando o chamador também não aceita a chave do
        // servidor não existe caminho nenhum e não há o que perguntar ao banco.
        // É o estado da instalação padrão (flag desligada, SECRET_ENCRYPTION_KEY
        // vazia), e é o que devolve a importação ao custo que ela tinha antes de
        // o AiCategorySuggester deixar de ser condicional: zero consulta.
        if (!serverFallbackAllowed && !cipher.isAvailable()) {
            return Optional.empty();
        }
        Optional<UserAiSettings> settings = repository.findByUserId(user.getId());
        if (settings.isEmpty()) {
            // A chave do servidor só é caminho quando existe de fato: com a
            // sentinela no lugar dela, chamar o provedor renderia um 401 vestido
            // de resposta do assistente
            return serverFallbackAllowed && serverKeyConfigured
                    ? Optional.of(serverCaller())
                    : Optional.empty();
        }
        return Optional.of(new ByokCaller(targetFor(user, settings.get())));
    }

    /**
     * Monta o destino a partir da linha do banco, decifrando a chave. A chave
     * fica viva apenas dentro do {@link AiCallTarget} que a chamada consome — não
     * há cache de texto em claro em lugar nenhum.
     */
    public AiCallTarget targetFor(User user, UserAiSettings settings) {
        String apiKey;
        try {
            apiKey = cipher.decrypt(settings.getApiKeyCipher(), user.getId().toString());
        } catch (SecretCipher.Unreadable e) {
            // a mensagem do cofre nomeia a chave-mestra e o tipo da falha, nunca
            // conteúdo — pode ir para o log
            log.warn("Chave de IA do usuário não pôde ser decifrada: {}", e.getMessage());
            throw new IllegalArgumentException(
                    "Sua chave de IA não pôde ser lida com a configuração atual do servidor. "
                            + "Cadastre a chave novamente nas opções de IA.");
        }
        AiProviderProperties.ProviderConfig config = properties.get(settings.getProvider());
        // modelo APOSENTADO pelo provedor (lista retired-models) cai no padrão
        // em vez de render 404 em todo chat — ver ProviderConfig.modelOrDefault
        String model = config.modelOrDefault(settings.getModel());
        if (!model.equals(settings.getModel())) {
            log.warn("Modelo de IA salvo está aposentado: provedor={} salvo={} usando={}",
                    settings.getProvider(), settings.getModel(), model);
        }
        return new AiCallTarget(settings.getProvider(), model,
                config.endpoint(), apiKey, config.maxTokens());
    }

    private AiChatCaller serverCaller() {
        return new ServerCaller();
    }

    /**
     * O caminho de sempre: {@code ChatClient} autoconfigurado pelo Spring AI em
     * cima de {@code spring.ai.openai.*}. A montagem do prompt é a mesma linha
     * que existia nos dois serviços antes do EC-107.
     */
    private class ServerCaller implements AiChatCaller {

        @Override
        public String complete(String systemPrompt, List<ChatTurn> history, String userPrompt) {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));
            for (ChatTurn turn : history) {
                // "assistant" vira AssistantMessage e "user" vira UserMessage:
                // sem essa distincao o modelo le as proprias respostas como se
                // fossem pedidos do usuario
                messages.add("assistant".equals(turn.role())
                        ? new AssistantMessage(turn.content())
                        : new UserMessage(turn.content()));
            }
            messages.add(new UserMessage(userPrompt));
            String content;
            try {
                content = serverChatClient.prompt(new Prompt(messages)).call().content();
            } catch (NonTransientAiException | TransientAiException e) {
                throw classifyServerFailure(e);
            } catch (ResourceAccessException e) {
                // sem stack pelo mesmo motivo do cliente BYOK: a cadeia de causas
                // de um erro de transporte pode carregar a requisição inteira
                log.warn("Falha de rede ao falar com o provedor de IA do servidor ({})",
                        e.getClass().getSimpleName());
                throw new AiProviderException(AiProviderException.Reason.NETWORK,
                        "Não foi possível falar com o provedor de IA.");
            } catch (org.springframework.web.client.RestClientException
                     | org.springframework.http.converter.HttpMessageConversionException e) {
                // Resposta que chegou mas não é o JSON do contrato (página HTML de
                // um proxy, corpo truncado, tipo de conteúdo inesperado): é falha
                // do provedor, não erro nosso — sem isto escapava como 500
                // genérico. Vem DEPOIS de ResourceAccessException, que também é
                // RestClientException e tem a mensagem própria de rede
                // Tempo esgotado no MEIO da leitura do corpo chega embrulhado como
                // erro de conversão (HttpMessageNotReadableException com um
                // SocketTimeoutException dentro), não como ResourceAccessException.
                // Era o caso de produção em 13/11: dizer "formato inesperado"
                // mandava procurar o problema no lugar errado
                if (org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e)
                        instanceof java.net.SocketTimeoutException) {
                    log.warn("Provedor de IA do servidor demorou além do teto de leitura ({})",
                            e.getClass().getSimpleName());
                    throw new AiProviderException(AiProviderException.Reason.NETWORK,
                            "O provedor de IA demorou demais para responder. Tente de novo.");
                }
                log.warn("Provedor de IA do servidor respondeu fora do contrato ({})",
                        e.getClass().getSimpleName());
                throw new AiProviderException(AiProviderException.Reason.PROVIDER,
                        "O provedor de IA respondeu num formato inesperado.");
            }
            // Em Spring AI 1.1.x content() pode ser nulo quando o Gemini bloqueia
            // ou não gera texto. Propagar nulo faria Mono.fromCallable completar
            // vazio e o controller responder 200 sem corpo.
            if (content == null || content.isBlank()) {
                log.warn("Provedor de IA do servidor respondeu sem conteúdo utilizável");
                throw AiProviderException.emptyResponse();
            }
            return content;
        }

        @Override
        public boolean userOwned() {
            return false;
        }

        @Override
        public String describe() {
            return "chave do servidor" + (serverModel.isBlank() ? "" : " / " + serverModel);
        }
    }

    /**
     * Traduz a falha do provedor no caminho da chave do SERVIDOR para o mesmo
     * {@link AiProviderException} do caminho BYOK.
     *
     * <p>Por que existe: em 12/11/2026 o Google aposentou o gemini-2.0-flash e o
     * provedor passou a responder 404 ("models/gemini-2.0-flash is no longer
     * available"). O Spring AI levanta isso como {@link NonTransientAiException}
     * (4xx) ou {@link TransientAiException} (5xx), nenhuma das duas tinha
     * handler, e o assistente respondia o 500 genérico "Ocorreu um erro
     * inesperado" — sem pista nenhuma de que o problema era o modelo.
     *
     * <p>O status vem do texto da exceção, que o {@code ResponseErrorHandler} do
     * {@code SpringAiRetryAutoConfiguration} monta como {@code "HTTP <status> -
     * <corpo>"} (formato conferido no 1.1.8 e fixado em teste). O CORPO não vai
     * para o log nem para a resposta: só o status e um texto escrito por nós.
     * As mensagens não falam de "sua chave" ou "sua conta" porque aqui a chave é
     * do servidor — o usuário não tem o que corrigir, só esperar ou cadastrar a
     * própria.
     */
    static AiProviderException classifyServerFailure(RuntimeException e) {
        int status = httpStatusOf(e);
        log.warn("Provedor de IA do servidor recusou a chamada: status={} tipo={}",
                status < 0 ? "desconhecido" : status, e.getClass().getSimpleName());
        if (status == 401 || status == 403) {
            return new AiProviderException(AiProviderException.Reason.AUTH,
                    "O provedor de IA recusou a chave do servidor.");
        }
        if (status == 404) {
            return new AiProviderException(AiProviderException.Reason.MODEL,
                    "O provedor de IA não reconheceu o modelo configurado no servidor.");
        }
        if (status == 429) {
            return new AiProviderException(AiProviderException.Reason.RATE_LIMIT,
                    "O provedor de IA atingiu o limite de uso do servidor. Tente de novo mais tarde.");
        }
        if (status == 400 || status == 422) {
            // mesmo critério do cliente BYOK: 400 do provedor quase sempre é
            // modelo ou parâmetro que aquele modelo não aceita
            return new AiProviderException(AiProviderException.Reason.MODEL,
                    "O provedor de IA recusou o pedido para o modelo configurado no servidor.");
        }
        return new AiProviderException(AiProviderException.Reason.PROVIDER,
                "O provedor de IA falhou ao responder.");
    }

    private static int httpStatusOf(Throwable e) {
        // percorre a cadeia: com retry ligado a exceção do handler pode vir embrulhada
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message == null) continue;
            Matcher matcher = HTTP_STATUS.matcher(message);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
            if (t.getCause() == t) break;
        }
        return -1;
    }

    private static final Pattern HTTP_STATUS = Pattern.compile("^HTTP (\\d{3})\\b");

    /** A chamada sai na chave do usuário, pelo cliente HTTP compatível. */
    private class ByokCaller implements AiChatCaller {

        private final AiCallTarget target;

        ByokCaller(AiCallTarget target) {
            this.target = target;
        }

        @Override
        public String complete(String systemPrompt, List<ChatTurn> history, String userPrompt) {
            return httpClient.complete(target, systemPrompt, history, userPrompt, properties.getTimeout());
        }

        @Override
        public boolean userOwned() {
            return true;
        }

        @Override
        public String describe() {
            return "chave do usuário / " + target.provider() + " / " + target.model();
        }
    }
}
