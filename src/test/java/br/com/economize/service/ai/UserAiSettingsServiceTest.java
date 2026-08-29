package br.com.economize.service.ai;

import br.com.economize.dto.ai.AiKeyTestRequest;
import br.com.economize.dto.ai.AiKeyTestResponse;
import br.com.economize.dto.ai.AiSettingsResponse;
import br.com.economize.dto.ai.SaveAiSettingsRequest;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.exception.ServiceUnavailableException;
import br.com.economize.model.User;
import br.com.economize.model.UserAiSettings;
import br.com.economize.repository.UserAiSettingsRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.security.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O catálogo vem INJETADO do contexto: o que este serviço valida (provedor,
 * modelo, rótulo do servidor) tem de bater com o catálogo que a aplicação
 * realmente monta, não com um construído à mão no teste.
 */
@SpringBootTest
class UserAiSettingsServiceTest {

    private static final String KEY_1 = "dGVzdGUtZGUtY2hhdmUtbWVzdHJhLTMyLWJ5dGVzISE=";
    private static final String KEY_2 = "c2VndW5kYS1jaGF2ZS1tZXN0cmEtZGUtMzItYnl0ZXM=";
    private static final String CHAVE = "sk-proj-chave-do-usuario-abcdefgh1234";

    @Autowired
    private AiProviderProperties properties;

    private UserRepository userRepository;
    private UserAiSettingsRepository repository;
    private AiChatCallerFactory callerFactory;
    private OpenAiCompatibleChatClient httpClient;

    private User ana;
    private User bruno;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        repository = mock(UserAiSettingsRepository.class);
        callerFactory = mock(AiChatCallerFactory.class);
        httpClient = mock(OpenAiCompatibleChatClient.class);

        ana = User.builder().id(UUID.randomUUID()).email("ana@economize.app").name("Ana").build();
        bruno = User.builder().id(UUID.randomUUID()).email("bruno@economize.app").name("Bruno").build();
        when(userRepository.findByEmail(ana.getEmail())).thenReturn(Optional.of(ana));
        when(userRepository.findByEmail(bruno.getEmail())).thenReturn(Optional.of(bruno));
    }

    private UserAiSettingsService service(SecretCipher cipher) {
        return new UserAiSettingsService(userRepository, repository, cipher, properties,
                callerFactory, httpClient, "gemini-2.0-flash");
    }

    private UserAiSettings storedFor(User user, SecretCipher cipher, AiProvider provider, String model) {
        String envelope = cipher.encrypt(CHAVE, user.getId().toString());
        return UserAiSettings.builder()
                .id(UUID.randomUUID()).user(user).provider(provider).model(model)
                .apiKeyCipher(envelope).masterKeyId(SecretCipher.keyIdOf(envelope))
                .apiKeyLast4(CHAVE.substring(CHAVE.length() - 4))
                .build();
    }

    // ------------------------------------------------------------------
    // A chave nunca volta
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A leitura devolve provedor, modelo e 4 caracteres — nunca a chave nem o texto cifrado")
    void currentShouldNeverExposeTheKey() {
        SecretCipher cipher = new SecretCipher(KEY_1, "k1", "");
        UserAiSettings stored = storedFor(ana, cipher, AiProvider.OPENAI, "gpt-4o-mini");
        when(repository.findByUserId(ana.getId())).thenReturn(Optional.of(stored));

        AiSettingsResponse response = service(cipher).current(ana.getEmail());

        assertThat(response.source()).isEqualTo(AiSettingsResponse.SOURCE_USER);
        assertThat(response.provider()).isEqualTo("OPENAI");
        assertThat(response.model()).isEqualTo("gpt-4o-mini");
        assertThat(response.keyStatus()).isEqualTo(AiSettingsResponse.KEY_OK);
        assertThat(response.keyLast4()).isEqualTo("1234").hasSize(4);
        assertThat(response.toString())
                .doesNotContain(CHAVE)
                .doesNotContain(stored.getApiKeyCipher());
    }

    @Test
    @DisplayName("Sem configuração própria, a leitura descreve a chave do servidor")
    void currentShouldDescribeServerDefaults() {
        when(repository.findByUserId(ana.getId())).thenReturn(Optional.empty());

        AiSettingsResponse response = service(new SecretCipher(KEY_1, "k1", "")).current(ana.getEmail());

        assertThat(response.source()).isEqualTo(AiSettingsResponse.SOURCE_SERVER);
        assertThat(response.provider()).isEqualTo("GEMINI");
        assertThat(response.model()).isEqualTo("gemini-2.0-flash");
        assertThat(response.keyStatus()).isEqualTo(AiSettingsResponse.KEY_SERVER);
        assertThat(response.keyLast4()).isNull();
        assertThat(response.byokAvailable()).isTrue();
    }

    @Test
    @DisplayName("Chave-mestra trocada: a leitura avisa UNREADABLE em vez de fingir que está tudo bem")
    void currentShouldReportUnreadableKey() {
        UserAiSettings stored = storedFor(ana, new SecretCipher(KEY_1, "k1", ""),
                AiProvider.OPENAI, "gpt-4o-mini");
        when(repository.findByUserId(ana.getId())).thenReturn(Optional.of(stored));

        AiSettingsResponse response = service(new SecretCipher(KEY_2, "k2", "")).current(ana.getEmail());

        assertThat(response.keyStatus()).isEqualTo(AiSettingsResponse.KEY_UNREADABLE);
        assertThat(response.source()).isEqualTo(AiSettingsResponse.SOURCE_USER);
    }

    // ------------------------------------------------------------------
    // Gravação
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Salvar cifra a chave: nada em claro chega ao banco, e o id da chave-mestra é gravado")
    void saveShouldEncryptBeforePersisting() {
        SecretCipher cipher = new SecretCipher(KEY_1, "k1", "");
        when(repository.findByUserId(ana.getId())).thenReturn(Optional.empty());
        when(repository.save(any(UserAiSettings.class))).thenAnswer(i -> i.getArgument(0));

        service(cipher).save(ana.getEmail(), new SaveAiSettingsRequest("openai", "gpt-4o-mini", CHAVE));

        ArgumentCaptor<UserAiSettings> gravado = ArgumentCaptor.forClass(UserAiSettings.class);
        verify(repository).save(gravado.capture());
        UserAiSettings settings = gravado.getValue();
        assertThat(settings.getApiKeyCipher()).doesNotContain(CHAVE).startsWith("v1:k1:");
        assertThat(settings.getMasterKeyId()).isEqualTo("k1");
        assertThat(settings.getApiKeyLast4()).isEqualTo("1234");
        assertThat(settings.getProvider()).isEqualTo(AiProvider.OPENAI);
        // e o que foi cifrado é exatamente o que o usuário mandou
        assertThat(cipher.decrypt(settings.getApiKeyCipher(), ana.getId().toString())).isEqualTo(CHAVE);
    }

    @Test
    @DisplayName("Salvar sem modelo usa o padrão do provedor em vez de recusar")
    void saveShouldDefaultTheModel() {
        SecretCipher cipher = new SecretCipher(KEY_1, "k1", "");
        when(repository.findByUserId(ana.getId())).thenReturn(Optional.empty());
        when(repository.save(any(UserAiSettings.class))).thenAnswer(i -> i.getArgument(0));

        AiSettingsResponse response = service(cipher)
                .save(ana.getEmail(), new SaveAiSettingsRequest("ANTHROPIC", "  ", CHAVE));

        assertThat(response.model()).isEqualTo(properties.get(AiProvider.ANTHROPIC).defaultModel());
    }

    @Test
    @DisplayName("Salvar substitui a configuração anterior: chave velha não é arquivada em lugar nenhum")
    void saveShouldReplaceInPlace() {
        SecretCipher cipher = new SecretCipher(KEY_1, "k1", "");
        UserAiSettings existente = storedFor(ana, cipher, AiProvider.GEMINI, "gemini-2.0-flash");
        String cifraAntiga = existente.getApiKeyCipher();
        when(repository.findByUserId(ana.getId())).thenReturn(Optional.of(existente));
        when(repository.save(any(UserAiSettings.class))).thenAnswer(i -> i.getArgument(0));

        service(cipher).save(ana.getEmail(),
                new SaveAiSettingsRequest("OPENROUTER", "openai/gpt-4o-mini", "sk-or-outra-chave-9999"));

        ArgumentCaptor<UserAiSettings> gravado = ArgumentCaptor.forClass(UserAiSettings.class);
        verify(repository).save(gravado.capture());
        assertThat(gravado.getValue().getId()).isEqualTo(existente.getId());
        assertThat(gravado.getValue().getApiKeyCipher()).isNotEqualTo(cifraAntiga);
        assertThat(gravado.getValue().getApiKeyLast4()).isEqualTo("9999");
    }

    @Test
    @DisplayName("Provedor inválido responde 400 e nem toca no banco")
    void saveShouldRejectUnknownProvider() {
        SecretCipher cipher = new SecretCipher(KEY_1, "k1", "");

        assertThatThrownBy(() -> service(cipher)
                .save(ana.getEmail(), new SaveAiSettingsRequest("llama-caseira", "x", CHAVE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Provedor inválido")
                .hasMessageContaining("OPENROUTER");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Modelo fora do catálogo responde 400 com a lista aceita junto")
    void saveShouldRejectUnknownModel() {
        SecretCipher cipher = new SecretCipher(KEY_1, "k1", "");

        assertThatThrownBy(() -> service(cipher)
                .save(ana.getEmail(), new SaveAiSettingsRequest("OPENAI", "gpt-inexistente", CHAVE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Modelo inválido")
                .hasMessageContaining("gpt-4o-mini");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Chave em formato impossível responde 400 — e a mensagem nunca ecoa a chave")
    void saveShouldRejectMalformedKeyWithoutEchoingIt() {
        SecretCipher cipher = new SecretCipher(KEY_1, "k1", "");
        String comEspaco = "sk-parte-um sk-parte-dois";

        assertThatThrownBy(() -> service(cipher)
                .save(ana.getEmail(), new SaveAiSettingsRequest("OPENAI", "gpt-4o-mini", comEspaco)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("espaços")
                .hasMessageNotContaining(comEspaco);

        String gigante = "s".repeat(properties.getMaxKeyLength() + 1);
        assertThatThrownBy(() -> service(cipher)
                .save(ana.getEmail(), new SaveAiSettingsRequest("OPENAI", "gpt-4o-mini", gigante)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(gigante);
    }

    @Test
    @DisplayName("Instalação sem chave-mestra responde 503 — jamais grava a chave em claro")
    void saveShouldRefuseWithoutVault() {
        UserAiSettingsService service = service(new SecretCipher("", "k1", ""));

        assertThatThrownBy(() -> service
                .save(ana.getEmail(), new SaveAiSettingsRequest("OPENAI", "gpt-4o-mini", CHAVE)))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(repository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Fronteira de dono
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Configuração de outro usuário é invisível: apagar responde 404, nunca 403")
    void deleteShouldReturnNotFoundForAnotherOwner() {
        SecretCipher cipher = new SecretCipher(KEY_1, "k1", "");
        UserAiSettings daAna = storedFor(ana, cipher, AiProvider.OPENAI, "gpt-4o-mini");
        when(repository.findByUserId(ana.getId())).thenReturn(Optional.of(daAna));
        when(repository.findByUserId(bruno.getId())).thenReturn(Optional.empty());
        UserAiSettingsService service = service(cipher);

        // controle positivo: a dona consegue
        service.delete(ana.getEmail());
        verify(repository).delete(daAna);

        // e o Bruno não alcança a linha da Ana por nenhum caminho
        assertThatThrownBy(() -> service.delete(bruno.getEmail()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Nenhuma configuração");
    }

    @Test
    @DisplayName("A leitura do Bruno mostra o padrão do servidor, nunca o provedor cadastrado pela Ana")
    void currentShouldNotLeakAnotherOwnersSettings() {
        SecretCipher cipher = new SecretCipher(KEY_1, "k1", "");
        when(repository.findByUserId(ana.getId()))
                .thenReturn(Optional.of(storedFor(ana, cipher, AiProvider.ANTHROPIC, "claude-sonnet-4-5")));
        when(repository.findByUserId(bruno.getId())).thenReturn(Optional.empty());

        AiSettingsResponse doBruno = service(cipher).current(bruno.getEmail());

        assertThat(doBruno.source()).isEqualTo(AiSettingsResponse.SOURCE_SERVER);
        assertThat(doBruno.provider()).isEqualTo("GEMINI");
        assertThat(doBruno.keyLast4()).isNull();
    }

    // ------------------------------------------------------------------
    // Teste de chave
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Teste com chave no corpo não grava nada e responde ok")
    void testShouldNotPersistInlineKey() {
        SecretCipher cipher = new SecretCipher(KEY_1, "k1", "");
        when(httpClient.complete(any(), anyString(), anyString(), any())).thenReturn("ok");

        AiKeyTestResponse response = service(cipher)
                .test(ana.getEmail(), new AiKeyTestRequest("OPENAI", "gpt-4o-mini", CHAVE));

        assertThat(response.ok()).isTrue();
        assertThat(response.provider()).isEqualTo("OPENAI");
        assertThat(response.toString()).doesNotContain(CHAVE);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Chave recusada devolve 200 com ok=false e motivo classificado, sem o corpo do provedor")
    void testShouldReportRefusalWithoutHttpError() {
        SecretCipher cipher = new SecretCipher(KEY_1, "k1", "");
        when(httpClient.complete(any(), anyString(), anyString(), any()))
                .thenThrow(new AiProviderException(AiProviderException.Reason.AUTH,
                        "O provedor recusou a chave cadastrada."));

        AiKeyTestResponse response = service(cipher)
                .test(ana.getEmail(), new AiKeyTestRequest("OPENAI", "gpt-4o-mini", CHAVE));

        assertThat(response.ok()).isFalse();
        assertThat(response.reason()).isEqualTo("AUTH");
        assertThat(response.message()).doesNotContain(CHAVE);
    }

    @Test
    @DisplayName("Teste sem corpo e sem configuração cadastrada responde 404")
    void testShouldReturnNotFoundWithoutSettings() {
        SecretCipher cipher = new SecretCipher(KEY_1, "k1", "");
        when(repository.findByUserId(ana.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(cipher).test(ana.getEmail(), null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // Catálogo
    // ------------------------------------------------------------------

    @Test
    @DisplayName("O catálogo traz os quatro provedores e avisa quando a instalação não tem cofre")
    void catalogShouldListProvidersAndVaultAvailability() {
        var comCofre = service(new SecretCipher(KEY_1, "k1", "")).catalog();
        assertThat(comCofre.byokAvailable()).isTrue();
        assertThat(comCofre.providers()).hasSize(4)
                .extracting(p -> p.id())
                .containsExactly("GEMINI", "OPENAI", "ANTHROPIC", "OPENROUTER");
        assertThat(comCofre.providers().get(0).models()).isNotEmpty();
        assertThat(comCofre.providers().get(0).defaultModel())
                .isEqualTo(comCofre.providers().get(0).models().get(0));

        assertThat(service(new SecretCipher("", "k1", "")).catalog().byokAvailable()).isFalse();
    }
}
