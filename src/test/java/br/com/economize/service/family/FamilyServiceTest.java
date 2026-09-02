package br.com.economize.service.family;

import br.com.economize.dto.family.FamilyRequests;
import br.com.economize.dto.family.FamilyResponses;
import br.com.economize.exception.ResourceConflictException;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.Category;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.FamilyGroup;
import br.com.economize.model.FamilyInvite;
import br.com.economize.model.FamilyMember;
import br.com.economize.model.User;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.ConnectorAccountRepository;
import br.com.economize.repository.FamilyGroupRepository;
import br.com.economize.repository.FamilyInviteRepository;
import br.com.economize.repository.FamilyMemberRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * As regras da casa (EC-149) com o repositório dublado: papéis, convite, um
 * grupo por usuário e o que se compartilha. A consulta compartilhada em si é
 * provada contra banco em {@code FamilyRepositoryTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FamilyService (EC-149)")
class FamilyServiceTest {

    private static final String ANA = "ana@economize.dev";
    private static final String BIA = "bia@economize.dev";

    @Mock
    private FamilyGroupRepository groupRepository;
    @Mock
    private FamilyMemberRepository memberRepository;
    @Mock
    private FamilyInviteRepository inviteRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ConnectorAccountRepository connectorAccountRepository;

    @InjectMocks
    private FamilyService service;

    private User ana;
    private User bia;
    private FamilyGroup casa;
    private FamilyMember anaOwner;
    private FamilyMember biaMember;

    @BeforeEach
    void setUp() {
        ana = User.builder().id(UUID.randomUUID()).name("Ana").email(ANA).password("x").build();
        bia = User.builder().id(UUID.randomUUID()).name("Bia").email(BIA).password("x").build();
        when(userRepository.findByEmail(ANA)).thenReturn(Optional.of(ana));
        when(userRepository.findByEmail(BIA)).thenReturn(Optional.of(bia));

        casa = FamilyGroup.builder().id(UUID.randomUUID()).name("Casa").owner(ana).build();
        anaOwner = FamilyMember.builder().id(UUID.randomUUID()).group(casa).user(ana)
                .role(FamilyMember.Role.OWNER).shareScope(FamilyMember.ShareScope.TOTALS)
                .joinedAt(OffsetDateTime.now()).build();
        biaMember = FamilyMember.builder().id(UUID.randomUUID()).group(casa).user(bia)
                .role(FamilyMember.Role.MEMBER).shareScope(FamilyMember.ShareScope.TOTALS)
                .joinedAt(OffsetDateTime.now()).build();

        when(groupRepository.save(any())).thenAnswer(inv -> {
            FamilyGroup g = inv.getArgument(0);
            if (g.getId() == null) g.setId(UUID.randomUUID());
            return g;
        });
        when(memberRepository.save(any())).thenAnswer(inv -> {
            FamilyMember m = inv.getArgument(0);
            if (m.getId() == null) m.setId(UUID.randomUUID());
            if (m.getJoinedAt() == null) m.setJoinedAt(OffsetDateTime.now());
            return m;
        });
        when(inviteRepository.save(any())).thenAnswer(inv -> {
            FamilyInvite i = inv.getArgument(0);
            if (i.getId() == null) i.setId(UUID.randomUUID());
            return i;
        });
        when(inviteRepository.findFirstByGroupIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.empty());
    }

    private void anaEBiaNaCasa() {
        when(memberRepository.findByUserId(ana.getId())).thenReturn(Optional.of(anaOwner));
        when(memberRepository.findByUserId(bia.getId())).thenReturn(Optional.of(biaMember));
        when(memberRepository.existsByUserId(ana.getId())).thenReturn(true);
        when(memberRepository.existsByUserId(bia.getId())).thenReturn(true);
        when(memberRepository.findAllByGroupIdOrderByJoinedAtAsc(casa.getId()))
                .thenReturn(List.of(anaOwner, biaMember));
        when(memberRepository.findByIdAndGroupId(anaOwner.getId(), casa.getId())).thenReturn(Optional.of(anaOwner));
        when(memberRepository.findByIdAndGroupId(biaMember.getId(), casa.getId())).thenReturn(Optional.of(biaMember));
    }

    // ------------------------------------------------------------ criar / ler

    @Test
    @DisplayName("Criar a casa me faz OWNER, compartilhando TOTALS, com o nome padrão")
    void criarFazDeMimOwnerComTotals() {
        when(memberRepository.existsByUserId(ana.getId())).thenReturn(false);
        when(memberRepository.findAllByGroupIdOrderByJoinedAtAsc(any()))
                .thenAnswer(inv -> {
                    ArgumentCaptor<FamilyMember> captor = ArgumentCaptor.forClass(FamilyMember.class);
                    verify(memberRepository).save(captor.capture());
                    return List.of(captor.getValue());
                });

        FamilyResponses.FamilyResponse response = service.create(ANA, new FamilyRequests.CreateFamily("   "));

        assertThat(response.name()).isEqualTo("Casa");
        assertThat(response.role()).isEqualTo("OWNER");
        assertThat(response.members()).hasSize(1);
        assertThat(response.members().get(0).isMe()).isTrue();
        assertThat(response.members().get(0).name()).isEqualTo("Ana");
        // Padrão TOTALS: entrar numa casa e não mostrar nada seria estranho;
        // abrir as linhas é um passo que a pessoa dá quando quiser
        assertThat(response.mySharing().shareScope()).isEqualTo("TOTALS");
        assertThat(response.mySharing().includeUnassigned()).isTrue();
        assertThat(response.mySharing().hiddenCategoryIds()).isEmpty();
        assertThat(response.invite()).isNull();
    }

    @Test
    @DisplayName("Criar sem corpo funciona igual")
    void criarSemCorpo() {
        when(memberRepository.existsByUserId(ana.getId())).thenReturn(false);
        when(memberRepository.findAllByGroupIdOrderByJoinedAtAsc(any())).thenReturn(List.of(anaOwner));

        assertThat(service.create(ANA, null).name()).isEqualTo("Casa");
    }

    @Test
    @DisplayName("Um grupo por usuário: criar já tendo casa responde 409")
    void criarJaTendoCasa409() {
        when(memberRepository.existsByUserId(ana.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.create(ANA, new FamilyRequests.CreateFamily("Outra")))
                .isInstanceOf(ResourceConflictException.class);
        verify(groupRepository, never()).save(any());
    }

    @Test
    @DisplayName("Sem casa, toda leitura ou ação responde 404")
    void semCasa404() {
        when(memberRepository.findByUserId(ana.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(ANA)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.rename(ANA, new FamilyRequests.UpdateFamily("X")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(ANA)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.issueInvite(ANA)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.removeMember(ANA, "me")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.updateSharing(ANA,
                new FamilyRequests.UpdateSharing("NONE", null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("O GET devolve o convite vivo SEM o código")
    void getNaoDevolveCodigo() {
        anaEBiaNaCasa();
        FamilyInvite vivo = FamilyInvite.builder().id(UUID.randomUUID()).group(casa).createdBy(ana)
                .codeHash("hash").expiresAt(OffsetDateTime.now().plusDays(3)).build();
        when(inviteRepository.findFirstByGroupIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(casa.getId()), any())).thenReturn(Optional.of(vivo));

        FamilyResponses.FamilyResponse response = service.get(BIA);

        assertThat(response.role()).isEqualTo("MEMBER");
        assertThat(response.invite()).isNotNull();
        assertThat(response.invite().code()).isNull();
        assertThat(response.invite().expiresAt()).isEqualTo(vivo.getExpiresAt());
        assertThat(response.members()).extracting(FamilyResponses.MemberItem::isMe)
                .containsExactly(false, true);
    }

    // ------------------------------------------------------------ convite

    @Test
    @DisplayName("Só o OWNER emite convite")
    void soOwnerEmite() {
        anaEBiaNaCasa();

        assertThatThrownBy(() -> service.issueInvite(BIA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Só quem criou a casa");
        verify(inviteRepository, never()).save(any());
    }

    @Test
    @DisplayName("Emitir gera 8 caracteres do alfabeto, guarda só o hash, vale 7 dias e invalida o anterior")
    void emitirGeraCodigoEGuardaHash() {
        anaEBiaNaCasa();

        FamilyResponses.InviteInfo issued = service.issueInvite(ANA);

        assertThat(issued.code()).hasSize(InviteCode.LENGTH);
        assertThat(issued.code().chars().allMatch(c -> InviteCode.ALPHABET.indexOf(c) >= 0))
                .as("todo caractere sai do alfabeto sem ambiguidade").isTrue();

        ArgumentCaptor<FamilyInvite> captor = ArgumentCaptor.forClass(FamilyInvite.class);
        verify(inviteRepository).save(captor.capture());
        FamilyInvite stored = captor.getValue();
        assertThat(stored.getCodeHash()).isEqualTo(InviteCode.hash(issued.code()));
        assertThat(stored.getCodeHash()).isNotEqualTo(issued.code());
        assertThat(stored.getExpiresAt())
                .isCloseTo(OffsetDateTime.now().plus(FamilyService.INVITE_TTL), within(5, ChronoUnit.SECONDS));
        assertThat(issued.expiresAt()).isEqualTo(stored.getExpiresAt());

        // um convite vivo por casa: o anterior não usado sai antes do novo entrar
        verify(inviteRepository).deleteByGroupIdAndUsedAtIsNull(casa.getId());
        verify(inviteRepository).deleteByExpiresAtBefore(any());
    }

    @Test
    @DisplayName("Entrar normaliza o código (minúsculas, espaços, hífens) e marca o convite como usado")
    void entrarNormalizaOCodigo() {
        when(memberRepository.existsByUserId(bia.getId())).thenReturn(false);
        FamilyInvite vivo = conviteVivo("ABCD2345");
        when(memberRepository.findAllByGroupIdOrderByJoinedAtAsc(casa.getId()))
                .thenAnswer(inv -> List.of(anaOwner, capturedMember()));

        FamilyResponses.FamilyResponse response = service.join(BIA, new FamilyRequests.JoinFamily(" abcd-2345 "));

        // o aceite é o UPDATE condicional do repositório, não um set no objeto
        verify(inviteRepository).claim(eq(vivo.getId()), any());
        assertThat(response.role()).isEqualTo("MEMBER");
        assertThat(response.mySharing().shareScope()).isEqualTo("TOTALS");
        assertThat(response.members()).hasSize(2);
        FamilyMember saved = capturedMember();
        assertThat(saved.getGroup()).isSameAs(casa);
        assertThat(saved.getRole()).isEqualTo(FamilyMember.Role.MEMBER);
    }

    @Test
    @DisplayName("Convite inexistente, vencido e usado respondem o MESMO 404")
    void conviteInvalidoExpiradoUsadoRespondemIgual() {
        when(memberRepository.existsByUserId(bia.getId())).thenReturn(false);

        when(inviteRepository.findByCodeHash(anyString())).thenReturn(Optional.empty());
        Throwable inexistente = falhaAoEntrar("AAAA2222");

        FamilyInvite vencido = conviteVivo("BBBB3333");
        vencido.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        Throwable expirado = falhaAoEntrar("BBBB3333");

        FamilyInvite usado = conviteVivo("CCCC4444");
        usado.setUsedAt(OffsetDateTime.now().minusHours(1));
        Throwable jaUsado = falhaAoEntrar("CCCC4444");

        assertThat(inexistente).isInstanceOf(ResourceNotFoundException.class);
        assertThat(expirado).isInstanceOf(ResourceNotFoundException.class);
        assertThat(jaUsado).isInstanceOf(ResourceNotFoundException.class);
        // a mensagem é uma só: distinguir daria um oráculo sobre o estado dos convites
        assertThat(expirado.getMessage()).isEqualTo(inexistente.getMessage());
        assertThat(jaUsado.getMessage()).isEqualTo(inexistente.getMessage());
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("Uso único sob corrida: se outro join ganhou o aceite, é o mesmo 404 e ninguém entra")
    void conviteJaAceitoPorOutroNaCorrida404() {
        when(memberRepository.existsByUserId(bia.getId())).thenReturn(false);
        FamilyInvite vivo = conviteVivo("EEEE6666");
        // a leitura viu o convite vivo, mas o UPDATE condicional não achou linha:
        // entre um e outro, alguém já gravou used_at (ou o convite venceu)
        when(inviteRepository.claim(eq(vivo.getId()), any())).thenReturn(0);

        Throwable falha = falhaAoEntrar("EEEE6666");

        assertThat(falha).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage(FamilyService.INVALID_INVITE_MESSAGE);
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("Quem já tem casa recebe 409 ANTES de o código ser olhado")
    void entrarJaTendoCasa409AntesDoCodigo() {
        when(memberRepository.existsByUserId(bia.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.join(BIA, new FamilyRequests.JoinFamily("ZZZZ9999")))
                .isInstanceOf(ResourceConflictException.class);
        // sem consulta ao convite: a rota não pode servir de oráculo a quem já está dentro
        verifyNoInteractions(inviteRepository);
    }

    // ------------------------------------------------------------ sair / remover

    @Test
    @DisplayName("OWNER não sai — nem por 'me' nem pelo próprio id")
    void ownerNaoSai() {
        anaEBiaNaCasa();

        assertThatThrownBy(() -> service.removeMember(ANA, "me"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("não sai");
        assertThatThrownBy(() -> service.removeMember(ANA, anaOwner.getId().toString()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(memberRepository, never()).delete(any());
    }

    @Test
    @DisplayName("MEMBER sai sozinho, por 'me' ou pelo próprio id")
    void memberSaiSozinho() {
        anaEBiaNaCasa();

        service.removeMember(BIA, "ME");
        service.removeMember(BIA, biaMember.getId().toString());

        verify(memberRepository, org.mockito.Mockito.times(2)).delete(biaMember);
    }

    @Test
    @DisplayName("MEMBER não remove outro; OWNER remove qualquer outro")
    void quemRemoveQuem() {
        anaEBiaNaCasa();

        assertThatThrownBy(() -> service.removeMember(BIA, anaOwner.getId().toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remover outros membros");
        verify(memberRepository, never()).delete(any());

        service.removeMember(ANA, biaMember.getId().toString());
        verify(memberRepository).delete(biaMember);
    }

    @Test
    @DisplayName("Membro de outra casa é 404; id malformado é 400")
    void membroDesconhecido() {
        anaEBiaNaCasa();
        UUID deOutraCasa = UUID.randomUUID();
        when(memberRepository.findByIdAndGroupId(deOutraCasa, casa.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeMember(ANA, deOutraCasa.toString()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.removeMember(ANA, "nao-e-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------ renomear / apagar

    @Test
    @DisplayName("Só o OWNER renomeia e apaga")
    void soOwnerRenomeiaEApaga() {
        anaEBiaNaCasa();

        assertThatThrownBy(() -> service.rename(BIA, new FamilyRequests.UpdateFamily("Lar")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.delete(BIA))
                .isInstanceOf(IllegalArgumentException.class);
        verify(groupRepository, never()).save(any());
        verify(groupRepository, never()).delete(any());

        FamilyResponses.FamilyResponse renamed = service.rename(ANA, new FamilyRequests.UpdateFamily("  Lar  "));
        assertThat(renamed.name()).isEqualTo("Lar");
    }

    @Test
    @DisplayName("Apagar a casa leva convites, membros e o grupo")
    void apagarLevaTudo() {
        anaEBiaNaCasa();
        FamilyInvite invite = conviteVivo("DDDD5555");
        when(inviteRepository.findAllByGroupId(casa.getId())).thenReturn(List.of(invite));

        service.delete(ANA);

        verify(inviteRepository).deleteAll(List.of(invite));
        verify(memberRepository).deleteAll(List.of(anaOwner, biaMember));
        verify(groupRepository).delete(casa);
    }

    // ------------------------------------------------------------ o que eu compartilho

    @Test
    @DisplayName("Escopo fora do enum responde 400 dizendo os valores")
    void escopoInvalido400() {
        anaEBiaNaCasa();

        assertThatThrownBy(() -> service.updateSharing(BIA,
                new FamilyRequests.UpdateSharing("TUDO", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NONE, TOTALS ou TRANSACTIONS");
    }

    @Test
    @DisplayName("Categoria fora do meu catálogo ou conta que não é minha respondem 400")
    void categoriaOuContaAlheia400() {
        anaEBiaNaCasa();
        UUID alheia = UUID.randomUUID();
        when(categoryRepository.findAccessible(alheia, bia.getId())).thenReturn(Optional.empty());
        when(connectorAccountRepository.findByIdAndUserId(alheia, bia.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSharing(BIA,
                new FamilyRequests.UpdateSharing("TOTALS", List.of(alheia), null, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Categoria");
        assertThatThrownBy(() -> service.updateSharing(BIA,
                new FamilyRequests.UpdateSharing("TOTALS", null, List.of(alheia), true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conta");
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("O PUT substitui os quatro campos de uma vez; escopo em minúsculas passa; nulo vira padrão")
    void putSubstituiTudo() {
        anaEBiaNaCasa();
        biaMember.getHiddenCategoryIds().add(UUID.randomUUID());
        biaMember.getSharedAccountIds().add(UUID.randomUUID());
        UUID saude = UUID.randomUUID();
        UUID nubank = UUID.randomUUID();
        when(categoryRepository.findAccessible(saude, bia.getId()))
                .thenReturn(Optional.of(Category.builder().id(saude).name("Saúde").build()));
        when(connectorAccountRepository.findByIdAndUserId(nubank, bia.getId()))
                .thenReturn(Optional.of(ConnectorAccount.builder().id(nubank).user(bia).build()));

        FamilyResponses.SharingSettings settings = service.updateSharing(BIA,
                new FamilyRequests.UpdateSharing("transactions", List.of(saude, saude), List.of(nubank), null));

        assertThat(settings.shareScope()).isEqualTo("TRANSACTIONS");
        assertThat(settings.hiddenCategoryIds()).containsExactly(saude);
        assertThat(settings.sharedAccountIds()).containsExactly(nubank);
        assertThat(settings.includeUnassigned()).isTrue();
        assertThat(biaMember.getHiddenCategoryIds()).containsExactly(saude);
        assertThat(biaMember.getSharedAccountIds()).containsExactly(nubank);

        FamilyResponses.SharingSettings cleared = service.updateSharing(BIA,
                new FamilyRequests.UpdateSharing("NONE", List.of(), List.of(), false));
        assertThat(cleared.hiddenCategoryIds()).isEmpty();
        assertThat(cleared.includeUnassigned()).isFalse();
    }

    // ------------------------------------------------------------ apoio

    private FamilyInvite conviteVivo(String code) {
        FamilyInvite invite = FamilyInvite.builder().id(UUID.randomUUID()).group(casa).createdBy(ana)
                .codeHash(InviteCode.hash(code))
                .expiresAt(OffsetDateTime.now().plusDays(7))
                .build();
        when(inviteRepository.findByCodeHash(InviteCode.hash(code))).thenReturn(Optional.of(invite));
        // o aceite atômico encontra a linha: uma atualizada
        when(inviteRepository.claim(eq(invite.getId()), any())).thenReturn(1);
        return invite;
    }

    private Throwable falhaAoEntrar(String code) {
        try {
            service.join(BIA, new FamilyRequests.JoinFamily(code));
            throw new AssertionError("esperava falha ao entrar com " + code);
        } catch (RuntimeException e) {
            return e;
        }
    }

    private FamilyMember capturedMember() {
        ArgumentCaptor<FamilyMember> captor = ArgumentCaptor.forClass(FamilyMember.class);
        verify(memberRepository).save(captor.capture());
        return captor.getValue();
    }
}
