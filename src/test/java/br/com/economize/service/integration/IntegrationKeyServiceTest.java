package br.com.economize.service.integration;

import br.com.economize.dto.integration.IntegrationKeyResponses;
import br.com.economize.exception.ResourceConflictException;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.IntegrationKey;
import br.com.economize.model.User;
import br.com.economize.repository.IntegrationKeyRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * As regras da chave de integração (V44), com o banco dublado: o que é
 * gravado na criação, o teto, a revogação e cada motivo pelo qual uma chave
 * deixa de autenticar. A cadeia de segurança que usa isto tem teste próprio
 * ({@code IntegrationRoutesSecurityTest}), e o caminho inteiro contra banco
 * está em {@code IntegrationKeyFlowTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IntegrationKeyService — chave de integração (V44)")
class IntegrationKeyServiceTest {

    private static final String EMAIL = "dono@economize.app";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final OffsetDateTime AGORA = OffsetDateTime.of(2026, 11, 11, 12, 0, 0, 0, ZoneOffset.UTC);
    /** Formato válido: "eco_" e 43 caracteres de base64url. */
    private static final String SEGREDO = "eco_" + "A".repeat(43);

    @Mock
    private IntegrationKeyRepository repository;
    @Mock
    private UserRepository userRepository;
    @InjectMocks
    private IntegrationKeyService service;

    @BeforeEach
    void setUp() {
        User dono = User.builder().id(USER_ID).name("Dono").email(EMAIL).password("x").build();
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(dono));
        lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.of(dono));
        lenient().when(repository.save(any(IntegrationKey.class))).thenAnswer(call -> call.getArgument(0));
    }

    private IntegrationKey gravada(OffsetDateTime lastUsedAt, OffsetDateTime revokedAt) {
        return IntegrationKey.builder()
                .id(UUID.randomUUID()).userId(USER_ID).name("UniOver")
                .keyHash(IntegrationKeyService.hash(SEGREDO)).prefix(SEGREDO.substring(0, 12))
                .createdAt(AGORA.minusDays(3)).lastUsedAt(lastUsedAt).revokedAt(revokedAt)
                .build();
    }

    // ------------------------------------------------------------ criação

    @Test
    @DisplayName("Criar devolve o segredo e grava só o hash e o começo dele")
    void criaGravandoSoOHash() {
        IntegrationKeyResponses.IntegrationKeyCreated criada = service.create(EMAIL, "  UniOver  ");

        assertThat(criada.secret()).matches("^eco_[A-Za-z0-9_-]{43}$");

        ArgumentCaptor<IntegrationKey> captor = ArgumentCaptor.forClass(IntegrationKey.class);
        verify(repository).save(captor.capture());
        IntegrationKey salva = captor.getValue();
        assertThat(salva.getUserId()).isEqualTo(USER_ID);
        assertThat(salva.getName()).isEqualTo("UniOver");
        assertThat(salva.getKeyHash())
                .isEqualTo(IntegrationKeyService.hash(criada.secret()))
                .matches("^[0-9a-f]{64}$");
        assertThat(salva.getPrefix()).isEqualTo(criada.secret().substring(0, 12));
        // Nenhum campo da linha carrega o segredo: é a propriedade que faz um
        // vazamento do banco não virar vazamento de credencial
        assertThat(salva.getKeyHash()).doesNotContain(criada.secret());
        assertThat(salva.getName()).doesNotContain(criada.secret());
        assertThat(salva.getPrefix()).hasSize(12);
    }

    @Test
    @DisplayName("Duas chaves nunca saem iguais")
    void segredosDiferentes() {
        assertThat(service.create(EMAIL, "a").secret()).isNotEqualTo(service.create(EMAIL, "b").secret());
    }

    @Test
    @DisplayName("A sexta chave ativa responde conflito e nada é gravado")
    void tetoDeCincoChaves() {
        when(repository.countByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(5L);

        assertThatThrownBy(() -> service.create(EMAIL, "sexta"))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessage(IntegrationKeyService.TOO_MANY_KEYS);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Com quatro ativas a quinta entra — revogada não conta no teto")
    void quintaChaveEntra() {
        when(repository.countByUserIdAndRevokedAtIsNull(USER_ID)).thenReturn(4L);

        assertThat(service.create(EMAIL, "quinta").secret()).startsWith("eco_");
    }

    // ------------------------------------------------------------ revogação

    @Test
    @DisplayName("Revogar grava a data; revogar de novo mantém a primeira")
    void revoga() {
        IntegrationKey chave = gravada(null, null);
        when(repository.findByIdAndUserId(chave.getId(), USER_ID)).thenReturn(Optional.of(chave));

        service.revoke(EMAIL, chave.getId());
        OffsetDateTime primeira = chave.getRevokedAt();
        assertThat(primeira).isNotNull();

        service.revoke(EMAIL, chave.getId());
        assertThat(chave.getRevokedAt()).isEqualTo(primeira);
        verify(repository).save(chave);
    }

    @Test
    @DisplayName("Chave de outra pessoa não existe para quem pede: 404, e nada muda")
    void naoRevogaChaveAlheia() {
        UUID alheia = UUID.randomUUID();
        // A busca leva o dono na cláusula; a chave de B não volta para A
        when(repository.findByIdAndUserId(alheia, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(EMAIL, alheia))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage(IntegrationKeyService.KEY_NOT_FOUND);
        verify(repository, never()).save(any());
    }

    // ------------------------------------------------------------ conferência

    @Test
    @DisplayName("Chave válida autentica o dono e marca o primeiro uso")
    void autentica() {
        IntegrationKey chave = gravada(null, null);
        when(repository.findByKeyHash(chave.getKeyHash())).thenReturn(Optional.of(chave));

        assertThat(service.authenticate(SEGREDO, AGORA)).contains(EMAIL);
        verify(repository).touchLastUsed(chave.getId(), AGORA, AGORA.minusMinutes(5));
    }

    @Test
    @DisplayName("Uso recente não regrava o último uso; uso antigo regrava")
    void ultimoUsoComIntervalo() {
        IntegrationKey recente = gravada(AGORA.minusMinutes(2), null);
        when(repository.findByKeyHash(recente.getKeyHash())).thenReturn(Optional.of(recente));
        assertThat(service.authenticate(SEGREDO, AGORA)).contains(EMAIL);
        verify(repository, never()).touchLastUsed(any(), any(), any());

        IntegrationKey antiga = gravada(AGORA.minusMinutes(6), null);
        when(repository.findByKeyHash(antiga.getKeyHash())).thenReturn(Optional.of(antiga));
        assertThat(service.authenticate(SEGREDO, AGORA)).contains(EMAIL);
        verify(repository).touchLastUsed(eq(antiga.getId()), eq(AGORA), any());
    }

    @Test
    @DisplayName("Chave revogada não autentica nem marca uso")
    void revogadaNaoAutentica() {
        IntegrationKey chave = gravada(null, AGORA.minusHours(1));
        when(repository.findByKeyHash(chave.getKeyHash())).thenReturn(Optional.of(chave));

        assertThat(service.authenticate(SEGREDO, AGORA)).isEmpty();
        verify(repository, never()).touchLastUsed(any(), any(), any());
    }

    @Test
    @DisplayName("Chave desconhecida não autentica")
    void desconhecidaNaoAutentica() {
        when(repository.findByKeyHash(any())).thenReturn(Optional.empty());

        assertThat(service.authenticate(SEGREDO, AGORA)).isEmpty();
    }

    @Test
    @DisplayName("Chave de conta apagada não autentica")
    void semDonoNaoAutentica() {
        IntegrationKey chave = gravada(null, null);
        when(repository.findByKeyHash(chave.getKeyHash())).thenReturn(Optional.of(chave));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThat(service.authenticate(SEGREDO, AGORA)).isEmpty();
    }

    @Test
    @DisplayName("Formato errado é recusado sem ir ao banco — inclusive um JWT")
    void malformadaNemConsulta() {
        for (String lixo : new String[]{
                null, "", "eco_", "eco_curta", SEGREDO + "A", "ECO_" + "A".repeat(43),
                "eco_" + "A".repeat(42) + "+", "cabecalho.corpo.assinatura"}) {
            assertThat(service.authenticate(lixo, AGORA)).as("valor: %s", lixo).isEmpty();
        }
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Hash que não confere com o gravado não autentica")
    void hashDivergenteNaoAutentica() {
        // Defesa em profundidade: mesmo que a busca devolva uma linha, a
        // conferência final compara o hash apresentado com o gravado
        IntegrationKey outra = gravada(null, null);
        outra.setKeyHash(IntegrationKeyService.hash("eco_" + "B".repeat(43)));
        when(repository.findByKeyHash(IntegrationKeyService.hash(SEGREDO))).thenReturn(Optional.of(outra));

        assertThat(service.authenticate(SEGREDO, AGORA)).isEmpty();
    }
}
