package br.com.economize.service.ai;

import br.com.economize.dto.ai.AiKeyTestRequest;
import br.com.economize.dto.ai.AiKeyTestResponse;
import br.com.economize.dto.ai.AiProviderCatalogResponse;
import br.com.economize.dto.ai.AiSettingsResponse;
import br.com.economize.dto.ai.SaveAiSettingsRequest;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.exception.ServiceUnavailableException;
import br.com.economize.model.User;
import br.com.economize.model.UserAiSettings;
import br.com.economize.repository.UserAiSettingsRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.security.SecretCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Ciclo de vida da configuração de IA do usuário — EC-107.
 *
 * <p><b>Fronteira de dono.</b> Não existe rota nem método que alcance a
 * configuração por id: tudo entra pelo e-mail do JWT e sai por
 * {@code findByUserId}. A conta de outro usuário não é 403 nem 404 por decisão
 * de código — ela simplesmente não é endereçável. O 404 aparece onde ele tem
 * sentido: apagar (ou testar) uma configuração que ESTA conta não tem.
 *
 * <p><b>A chave nunca volta.</b> Ela entra uma vez, é cifrada e some. Nenhum
 * método desta classe devolve a chave, nem cifrada, e nenhum log recebe o valor.
 */
@Slf4j
@Service
public class UserAiSettingsService {

    private final UserRepository userRepository;
    private final UserAiSettingsRepository repository;
    private final SecretCipher cipher;
    private final AiProviderProperties properties;
    private final AiChatCallerFactory callerFactory;
    private final OpenAiCompatibleChatClient httpClient;
    private final String serverModel;

    public UserAiSettingsService(UserRepository userRepository,
                                 UserAiSettingsRepository repository,
                                 SecretCipher cipher,
                                 AiProviderProperties properties,
                                 AiChatCallerFactory callerFactory,
                                 OpenAiCompatibleChatClient httpClient,
                                 @Value("${spring.ai.openai.chat.options.model:}") String serverModel) {
        this.userRepository = userRepository;
        this.repository = repository;
        this.cipher = cipher;
        this.properties = properties;
        this.callerFactory = callerFactory;
        this.httpClient = httpClient;
        this.serverModel = serverModel;
    }

    /** Catálogo para o app montar o seletor — nada aqui depende do usuário. */
    public AiProviderCatalogResponse catalog() {
        List<AiProviderCatalogResponse.ProviderOption> options = Arrays.stream(AiProvider.values())
                .map(provider -> {
                    AiProviderProperties.ProviderConfig config = properties.get(provider);
                    return new AiProviderCatalogResponse.ProviderOption(
                            provider.name(), config.label(), config.defaultModel(),
                            config.models(), config.apiKeyUrl());
                })
                .toList();
        return new AiProviderCatalogResponse(cipher.isAvailable(), options);
    }

    /** O que vale agora para esta conta. */
    @Transactional(readOnly = true)
    public AiSettingsResponse current(String email) {
        User user = requireUser(email);
        return repository.findByUserId(user.getId())
                .map(settings -> describe(user, settings))
                .orElseGet(this::serverDefaults);
    }

    /**
     * Grava (ou substitui) a configuração própria.
     *
     * <p>Não valida a chave contra o provedor — ver
     * {@link #test(String, AiKeyTestRequest)} para o porquê e para a alternativa
     * que o app tem.
     */
    @Transactional
    public AiSettingsResponse save(String email, SaveAiSettingsRequest request) {
        User user = requireUser(email);
        if (!cipher.isAvailable()) {
            throw new ServiceUnavailableException("Esta instalação não aceita chave própria de IA: "
                    + "o servidor está sem chave de criptografia configurada.");
        }

        AiProvider provider = requireProvider(request.provider());
        String model = requireModel(provider, request.model());
        String apiKey = requireApiKey(request.apiKey());

        // AAD = UUID do dono: amarra o texto cifrado a esta conta, ver SecretCipher
        String envelope = cipher.encrypt(apiKey, user.getId().toString());

        UserAiSettings settings = repository.findByUserId(user.getId())
                .orElseGet(() -> UserAiSettings.builder().user(user).build());
        settings.setProvider(provider);
        settings.setModel(model);
        settings.setApiKeyCipher(envelope);
        settings.setMasterKeyId(SecretCipher.keyIdOf(envelope));
        settings.setApiKeyLast4(last4(apiKey));

        UserAiSettings saved = repository.save(settings);
        // o log diz O QUE mudou, nunca com o quê
        log.info("Configuração de IA salva: provedor={} modelo={} user={}", provider, model, email);
        return describe(user, saved);
    }

    /** Volta para a chave do servidor. Sem configuração cadastrada, 404. */
    @Transactional
    public void delete(String email) {
        User user = requireUser(email);
        UserAiSettings settings = repository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Nenhuma configuração de IA cadastrada"));
        repository.delete(settings);
        log.info("Configuração de IA removida; a conta volta à chave do servidor. user={}", email);
    }

    /**
     * Testa uma chave contra o provedor, sob demanda.
     *
     * <p><b>Por que o teste NÃO acontece dentro do save.</b> Validar no cadastro
     * amarra "guardar minha configuração" à disponibilidade de um terceiro: uma
     * instabilidade do provedor, um limite de cota momentâneo ou uma saída de
     * rede bloqueada recusariam uma chave perfeitamente boa, e o usuário ficaria
     * sem nenhum caminho para salvar. Também cobraria uma chamada a cada save,
     * inclusive quando ele só quis trocar de modelo.
     *
     * <p><b>E por que ainda assim existe.</b> Salvar às cegas é UX ruim: a
     * primeira notícia de que a chave está errada chegaria como um chat quebrado.
     * Separando as duas ações, o app pode oferecer "Testar" ao lado de "Salvar",
     * testar antes mesmo de gravar (mandando {@code apiKey} no corpo) e mostrar o
     * resultado sem nunca bloquear o cadastro.
     *
     * <p><b>RISCO CONHECIDO E ACEITO: esta rota é um oráculo de validação de
     * chave de terceiros.</b> Com {@code apiKey} no corpo, quem estiver
     * autenticado faz o servidor perguntar a um provedor "esta chave presta?" e
     * recebe a resposta classificada em {@code reason} — o que serve para triar
     * uma lista de chaves vazadas usando o IP e a reputação deste servidor no
     * lugar dos do atacante. O que já limita o estrago: exige JWT válido; os
     * endpoints são fixos das properties, então não há SSRF (o atacante não
     * escolhe o destino); e a rota está no balde caro do
     * {@code RateLimitFilter}, a 10 requisições por minuto por token. O que
     * FALTA, e é decisão consciente de não construir agora: nenhum teto diário
     * nem por conta, e nada impede criar contas para multiplicar a cota. Se um
     * dia isto aparecer em log de abuso, o conserto é um contador por usuário
     * com janela de dia, não mexer no desenho desta rota.
     */
    public AiKeyTestResponse test(String email, AiKeyTestRequest request) {
        User user = requireUser(email);
        AiCallTarget target = testTarget(user, request);

        long started = System.nanoTime();
        try {
            httpClient.complete(target,
                    "Você é um verificador de conectividade. Responda exatamente: ok",
                    "ok", properties.getTestTimeout());
            return new AiKeyTestResponse(true, target.provider().name(), target.model(),
                    null, "Chave aceita pelo provedor.", elapsedMs(started));
        } catch (AiProviderException e) {
            // a mensagem já foi escrita por nós no cliente; o corpo do provedor
            // não chega até aqui
            return new AiKeyTestResponse(false, target.provider().name(), target.model(),
                    e.getReason().name(), e.getMessage(), elapsedMs(started));
        }
    }

    private AiCallTarget testTarget(User user, AiKeyTestRequest request) {
        boolean inlineKey = request != null && request.apiKey() != null && !request.apiKey().isBlank();
        if (inlineKey) {
            AiProvider provider = requireProvider(request.provider());
            String model = requireModel(provider, request.model());
            AiProviderProperties.ProviderConfig config = properties.get(provider);
            return new AiCallTarget(provider, model, config.endpoint(),
                    requireApiKey(request.apiKey()), config.maxTokens());
        }
        UserAiSettings settings = repository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Nenhuma configuração de IA cadastrada para testar"));
        return callerFactory.targetFor(user, settings);
    }

    private AiSettingsResponse serverDefaults() {
        return new AiSettingsResponse(AiSettingsResponse.SOURCE_SERVER, properties.serverProviderName(),
                serverModel, null, AiSettingsResponse.KEY_SERVER, cipher.isAvailable(), null);
    }

    /**
     * Monta a resposta e, de quebra, confere se a chave ainda é legível. A
     * decifragem custa microssegundos e é o que permite o app avisar "recadastre"
     * antes de o usuário descobrir pelo chat quebrado.
     */
    private AiSettingsResponse describe(User user, UserAiSettings settings) {
        String keyStatus = AiSettingsResponse.KEY_OK;
        try {
            cipher.decrypt(settings.getApiKeyCipher(), user.getId().toString());
        } catch (SecretCipher.Unreadable e) {
            log.warn("Chave de IA cadastrada está ilegível para user={}: {}", user.getEmail(), e.getMessage());
            keyStatus = AiSettingsResponse.KEY_UNREADABLE;
        }
        return new AiSettingsResponse(AiSettingsResponse.SOURCE_USER, settings.getProvider().name(),
                settings.getModel(), settings.getApiKeyLast4(), keyStatus,
                cipher.isAvailable(), settings.getUpdatedAt());
    }

    private AiProvider requireProvider(String raw) {
        return AiProvider.parse(raw).orElseThrow(() -> new IllegalArgumentException(
                "Provedor inválido. Aceitos: " + Arrays.stream(AiProvider.values())
                        .map(Enum::name).collect(Collectors.joining(", "))));
    }

    private String requireModel(AiProvider provider, String raw) {
        AiProviderProperties.ProviderConfig config = properties.get(provider);
        String model = raw == null ? "" : raw.trim();
        if (model.isEmpty()) return config.defaultModel();
        if (!config.models().contains(model)) {
            // a lista vai no texto: o app consegue exibir o erro sem outra chamada
            throw new IllegalArgumentException("Modelo inválido para " + provider.name()
                    + ". Aceitos: " + String.join(", ", config.models()));
        }
        return model;
    }

    /**
     * A chave não pode ser logada nem ecoada, então TODA mensagem daqui fala do
     * formato e nunca do conteúdo.
     */
    private String requireApiKey(String raw) {
        String apiKey = raw == null ? "" : raw.trim();
        if (apiKey.isEmpty()) {
            throw new IllegalArgumentException("Informe a chave do provedor");
        }
        if (apiKey.length() > properties.getMaxKeyLength()) {
            throw new IllegalArgumentException(
                    "Chave maior que o limite de " + properties.getMaxKeyLength() + " caracteres");
        }
        // espaço no meio é quase sempre cópia truncada ou dois valores colados;
        // deixar passar viraria um 401 do provedor difícil de explicar
        if (apiKey.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("A chave não pode conter espaços");
        }
        return apiKey;
    }

    private static String last4(String apiKey) {
        return apiKey.length() <= 4 ? null : apiKey.substring(apiKey.length() - 4);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }
}
