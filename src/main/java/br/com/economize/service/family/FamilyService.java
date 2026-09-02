package br.com.economize.service.family;

import br.com.economize.dto.family.FamilyRequests;
import br.com.economize.dto.family.FamilyResponses;
import br.com.economize.exception.ResourceConflictException;
import br.com.economize.exception.ResourceNotFoundException;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A casa (EC-149): criar, convidar, entrar, sair e decidir o que se
 * compartilha. A visão compartilhada em si mora em
 * {@link FamilyAnalyticsService}.
 *
 * <p>Toda rota parte do MEMBRO do usuário autenticado
 * ({@link FamilyMemberRepository#findByUserId}); nenhuma recebe id de grupo.
 * Sem membro, a resposta é 404 — a casa que não é minha não existe para mim.
 *
 * <p>As recusas de papel (MEMBER tentando o que é do OWNER) respondem 400 com
 * a regra escrita, e não 403: o projeto não usa 403 em nenhuma rota, e aqui
 * não há o que esconder — quem pergunta já está dentro da casa e a vê inteira
 * no GET.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FamilyService {

    static final Duration INVITE_TTL = Duration.ofDays(7);

    // Mensagem ÚNICA para código inexistente, usado ou vencido: distinguir os
    // três daria a quem tenta adivinhar um oráculo sobre o estado dos convites
    static final String INVALID_INVITE_MESSAGE = "Convite inválido ou expirado";
    static final String NO_FAMILY_MESSAGE = "Você ainda não faz parte de uma casa";
    static final String MEMBER_NOT_FOUND_MESSAGE = "Membro não encontrado";

    private final FamilyGroupRepository groupRepository;
    private final FamilyMemberRepository memberRepository;
    private final FamilyInviteRepository inviteRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final ConnectorAccountRepository connectorAccountRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional(readOnly = true)
    public FamilyResponses.FamilyResponse get(String email) {
        User user = requireUser(email);
        return toResponse(requireMembership(user.getId()));
    }

    @Transactional
    public FamilyResponses.FamilyResponse create(String email, FamilyRequests.CreateFamily request) {
        User user = requireUser(email);
        if (memberRepository.existsByUserId(user.getId())) {
            throw new ResourceConflictException("Você já faz parte de uma casa — saia dela antes de criar outra");
        }

        FamilyGroup group = groupRepository.save(FamilyGroup.builder()
                .name(normalizeName(request != null ? request.name() : null))
                .owner(user)
                .build());
        FamilyMember owner = memberRepository.save(FamilyMember.builder()
                .group(group)
                .user(user)
                .role(FamilyMember.Role.OWNER)
                .shareScope(FamilyMember.ShareScope.TOTALS)
                .build());
        log.info("Casa {} criada pelo usuário {}", group.getId(), user.getId());
        return toResponse(owner);
    }

    @Transactional
    public FamilyResponses.FamilyResponse rename(String email, FamilyRequests.UpdateFamily request) {
        User user = requireUser(email);
        FamilyMember me = requireMembership(user.getId());
        requireOwner(me, "renomear a casa");

        FamilyGroup group = me.getGroup();
        group.setName(normalizeName(request.name()));
        groupRepository.save(group);
        return toResponse(me);
    }

    /**
     * Desfaz a casa para todos. As remoções são explícitas, na ordem das
     * dependências, mesmo com o {@code ON DELETE CASCADE} da V19: o H2 dos
     * testes monta o schema pelo mapeamento JPA, que não carrega a regra do
     * FK, e um efeito que só existe em produção é um efeito que ninguém testa.
     */
    @Transactional
    public void delete(String email) {
        User user = requireUser(email);
        FamilyMember me = requireMembership(user.getId());
        requireOwner(me, "desfazer a casa");

        FamilyGroup group = me.getGroup();
        inviteRepository.deleteAll(inviteRepository.findAllByGroupId(group.getId()));
        memberRepository.deleteAll(memberRepository.findAllByGroupIdOrderByJoinedAtAsc(group.getId()));
        groupRepository.delete(group);
        log.info("Casa {} desfeita pelo dono {}", group.getId(), user.getId());
    }

    /**
     * Emite o convite. O código em claro sai daqui e de nenhum outro lugar: o
     * banco guarda só o hash, e o GET devolve o convite sem código.
     */
    @Transactional
    public FamilyResponses.InviteInfo issueInvite(String email) {
        User user = requireUser(email);
        FamilyMember me = requireMembership(user.getId());
        requireOwner(me, "convidar para a casa");

        // UTC e microssegundos: expiresAt volta na resposta agora (da entidade)
        // e no GET seguinte (do banco, TIMESTAMPTZ) — os dois têm que ser o
        // mesmo texto
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        // purga oportunista dos vencidos (sem job agendado no projeto) e, em
        // seguida, o convite vivo anterior: um por casa de cada vez
        inviteRepository.deleteByExpiresAtBefore(now);
        inviteRepository.deleteByGroupIdAndUsedAtIsNull(me.getGroup().getId());

        String code = InviteCode.generate(secureRandom);
        FamilyInvite invite = inviteRepository.save(FamilyInvite.builder()
                .group(me.getGroup())
                .createdBy(user)
                .codeHash(InviteCode.hash(code))
                .expiresAt(now.plus(INVITE_TTL))
                .build());
        return FamilyResponses.InviteInfo.issued(code, invite);
    }

    @Transactional
    public FamilyResponses.FamilyResponse join(String email, FamilyRequests.JoinFamily request) {
        User user = requireUser(email);
        // O 409 vem ANTES de olhar o código: quem já tem casa não pode usar a
        // rota para descobrir se um código existe (409 vs 404 seria o oráculo)
        if (memberRepository.existsByUserId(user.getId())) {
            throw new ResourceConflictException("Você já faz parte de uma casa — saia dela antes de entrar em outra");
        }

        FamilyInvite invite = requireLiveInvite(request.code());
        // O aceite é um UPDATE condicional, não um set + save: se outro join
        // ganhou a corrida por este mesmo código entre a leitura acima e agora
        // (ou o convite venceu no meio), zero linhas — e o MESMO 404, porque
        // para quem chegou depois o convite já estava usado
        if (inviteRepository.claim(invite.getId(), OffsetDateTime.now(ZoneOffset.UTC)) != 1) {
            throw new ResourceNotFoundException(INVALID_INVITE_MESSAGE);
        }

        FamilyMember member = memberRepository.save(FamilyMember.builder()
                .group(invite.getGroup())
                .user(user)
                .role(FamilyMember.Role.MEMBER)
                .shareScope(FamilyMember.ShareScope.TOTALS)
                .build());
        log.info("Usuário {} entrou na casa {}", user.getId(), invite.getGroup().getId());
        return toResponse(member);
    }

    /**
     * Sair ou remover. MEMBER só remove a si ({@code me} ou o próprio id);
     * OWNER remove qualquer outro e não sai — a casa sem dono não existe, então
     * o caminho dele é {@link #delete}. Apagar o membro leva os parâmetros de
     * compartilhamento junto: nada fica lembrado para uma casa futura.
     */
    @Transactional
    public void removeMember(String email, String memberIdOrMe) {
        User user = requireUser(email);
        FamilyMember me = requireMembership(user.getId());
        FamilyMember target = resolveTarget(me, memberIdOrMe);

        boolean self = target.getId().equals(me.getId());
        if (self && me.isOwner()) {
            throw new IllegalArgumentException(
                    "Quem criou a casa não sai dela — para desfazer a casa, apague-a");
        }
        if (!self && !me.isOwner()) {
            throw new IllegalArgumentException("Só quem criou a casa pode remover outros membros");
        }
        memberRepository.delete(target);
        log.info("Membro {} removido da casa {} por {}", target.getId(), me.getGroup().getId(), user.getId());
    }

    /**
     * O que EU compartilho. Cada id de categoria e de conta é validado como
     * MEU (do catálogo acessível a mim, da minha lista de contas) antes de
     * entrar: id alheio aqui não vazaria nada — a consulta compartilhada é
     * sempre sobre as minhas linhas —, mas gravaria uma regra sem efeito que a
     * tela mostraria como se valesse.
     */
    @Transactional
    public FamilyResponses.SharingSettings updateSharing(String email, FamilyRequests.UpdateSharing request) {
        User user = requireUser(email);
        FamilyMember me = requireMembership(user.getId());

        FamilyMember.ShareScope scope = parseScope(request.shareScope());

        Set<UUID> hidden = distinctNonNull(request.hiddenCategoryIds());
        for (UUID categoryId : hidden) {
            categoryRepository.findAccessible(categoryId, user.getId()).orElseThrow(() ->
                    new IllegalArgumentException("Categoria " + categoryId + " não existe ou não é sua"));
        }
        Set<UUID> shared = distinctNonNull(request.sharedAccountIds());
        for (UUID accountId : shared) {
            connectorAccountRepository.findByIdAndUserId(accountId, user.getId()).orElseThrow(() ->
                    new IllegalArgumentException("Conta " + accountId + " não existe ou não é sua"));
        }

        me.setShareScope(scope);
        // Substituição em lugar (clear + addAll) e não troca da referência: a
        // coleção gerenciada pelo Hibernate é quem sabe o que apagar e inserir
        me.getHiddenCategoryIds().clear();
        me.getHiddenCategoryIds().addAll(hidden);
        me.getSharedAccountIds().clear();
        me.getSharedAccountIds().addAll(shared);
        me.setIncludeUnassigned(request.includeUnassigned() == null || request.includeUnassigned());

        return FamilyResponses.SharingSettings.from(memberRepository.save(me));
    }

    // ------------------------------------------------------------------ apoio

    private FamilyResponses.FamilyResponse toResponse(FamilyMember me) {
        FamilyGroup group = me.getGroup();
        UUID myUserId = me.getUser().getId();
        List<FamilyResponses.MemberItem> members = memberRepository
                .findAllByGroupIdOrderByJoinedAtAsc(group.getId()).stream()
                .map(member -> FamilyResponses.MemberItem.from(member, myUserId))
                .toList();
        FamilyResponses.InviteInfo invite = inviteRepository
                .findFirstByGroupIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                        group.getId(), OffsetDateTime.now())
                .map(FamilyResponses.InviteInfo::withoutCode)
                .orElse(null);
        return new FamilyResponses.FamilyResponse(
                group.getId(), group.getName(), me.getRole().name(),
                members, FamilyResponses.SharingSettings.from(me), invite);
    }

    /**
     * O convite vivo para o código digitado — ou o MESMO 404 para inexistente,
     * usado e vencido. As três condições são avaliadas sempre (o {@code &} não
     * curto-circuita) e o hash é comparado em tempo constante: nem a mensagem
     * nem o tempo de resposta dizem em qual das três o código caiu.
     */
    private FamilyInvite requireLiveInvite(String rawCode) {
        String candidateHash = InviteCode.hash(InviteCode.normalize(rawCode));
        FamilyInvite invite = inviteRepository.findByCodeHash(candidateHash).orElse(null);
        OffsetDateTime now = OffsetDateTime.now();

        byte[] stored = invite != null
                ? invite.getCodeHash().getBytes(StandardCharsets.UTF_8) : new byte[0];
        boolean matches = MessageDigest.isEqual(candidateHash.getBytes(StandardCharsets.UTF_8), stored);
        boolean unused = invite != null && invite.getUsedAt() == null;
        boolean alive = invite != null && invite.getExpiresAt().isAfter(now);

        if (!(matches & unused & alive)) {
            throw new ResourceNotFoundException(INVALID_INVITE_MESSAGE);
        }
        return invite;
    }

    private FamilyMember resolveTarget(FamilyMember me, String memberIdOrMe) {
        if ("me".equalsIgnoreCase(memberIdOrMe.trim())) return me;
        UUID id;
        try {
            id = UUID.fromString(memberIdOrMe.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Identificador de membro inválido — use o id ou \"me\"");
        }
        // amarrado à MINHA casa: id de membro de outra casa não existe para mim
        return memberRepository.findByIdAndGroupId(id, me.getGroup().getId())
                .orElseThrow(() -> new ResourceNotFoundException(MEMBER_NOT_FOUND_MESSAGE));
    }

    private void requireOwner(FamilyMember me, String action) {
        if (!me.isOwner()) {
            throw new IllegalArgumentException("Só quem criou a casa pode " + action);
        }
    }

    private FamilyMember requireMembership(UUID userId) {
        return memberRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(NO_FAMILY_MESSAGE));
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }

    private static FamilyMember.ShareScope parseScope(String value) {
        try {
            return FamilyMember.ShareScope.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Escopo inválido — use NONE, TOTALS ou TRANSACTIONS");
        }
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) return FamilyGroup.DEFAULT_NAME;
        return name.trim();
    }

    private static Set<UUID> distinctNonNull(List<UUID> ids) {
        Set<UUID> result = new LinkedHashSet<>();
        if (ids == null) return result;
        ids.stream().filter(Objects::nonNull).forEach(result::add);
        return result;
    }
}
