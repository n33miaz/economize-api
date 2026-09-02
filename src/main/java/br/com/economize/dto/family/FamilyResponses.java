package br.com.economize.dto.family;

import br.com.economize.model.FamilyInvite;
import br.com.economize.model.FamilyMember;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * As respostas do grupo familiar (EC-149).
 *
 * <p>O código do convite aparece em UM lugar só: na resposta da emissão. O
 * {@code GET /family} devolve o convite vivo com {@code code} nulo — a tela
 * sabe que existe um e até quando vale, mas quem perdeu o código emite outro.
 */
public final class FamilyResponses {

    private FamilyResponses() {
    }

    public record FamilyResponse(
            UUID id,
            String name,
            /* papel de QUEM PERGUNTA: OWNER ou MEMBER */
            String role,
            List<MemberItem> members,
            SharingSettings mySharing,
            /* nulo quando não há convite vivo */
            InviteInfo invite
    ) {
    }

    public record MemberItem(
            UUID id,
            UUID userId,
            String name,
            String role,
            OffsetDateTime joinedAt,
            String shareScope,
            boolean isMe
    ) {
        public static MemberItem from(FamilyMember member, UUID callerUserId) {
            return new MemberItem(
                    member.getId(),
                    member.getUser().getId(),
                    member.getUser().getName(),
                    member.getRole().name(),
                    member.getJoinedAt(),
                    member.getShareScope().name(),
                    member.getUser().getId().equals(callerUserId));
        }
    }

    public record SharingSettings(
            String shareScope,
            List<UUID> hiddenCategoryIds,
            List<UUID> sharedAccountIds,
            boolean includeUnassigned
    ) {
        public static SharingSettings from(FamilyMember member) {
            return new SharingSettings(
                    member.getShareScope().name(),
                    // ordenadas para a resposta ser estável entre chamadas — um
                    // Set não promete ordem e a tela compara listas
                    member.getHiddenCategoryIds().stream().sorted().toList(),
                    member.getSharedAccountIds().stream().sorted().toList(),
                    member.isIncludeUnassigned());
        }
    }

    /**
     * O convite. {@code code} preenchido SÓ na emissão; nulo em toda leitura.
     */
    public record InviteInfo(String code, OffsetDateTime expiresAt) {
        public static InviteInfo issued(String code, FamilyInvite invite) {
            return new InviteInfo(code, invite.getExpiresAt());
        }

        public static InviteInfo withoutCode(FamilyInvite invite) {
            return new InviteInfo(null, invite.getExpiresAt());
        }
    }
}
