package br.com.economize.repository;

import br.com.economize.model.FamilyMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FamilyMemberRepository extends JpaRepository<FamilyMember, UUID> {

    // A porta de entrada de TODA rota de família: o membro do usuário do token.
    // Um usuário tem no máximo um (UNIQUE em user_id), então é Optional e não
    // lista; vazio significa "sem casa", e a rota responde 404.
    Optional<FamilyMember> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    List<FamilyMember> findAllByGroupIdOrderByJoinedAtAsc(UUID groupId);

    // Membro alvo de uma remoção, amarrado à casa de quem pede: id de membro de
    // OUTRA casa não retorna linha e a rota responde 404, sem confirmar que
    // aquele id existe (regra do EC-037).
    Optional<FamilyMember> findByIdAndGroupId(UUID id, UUID groupId);
}
