package br.com.economize.repository;

import br.com.economize.model.UserMfa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface UserMfaRepository extends JpaRepository<UserMfa, UUID> {

    Optional<UserMfa> findByUserId(UUID userId);

    /**
     * Consome o passo do TOTP na própria linha. O WHERE é a trava que faz
     * duas requisições com o mesmo código disputarem uma única aceitação.
     */
    @Modifying
    @Transactional
    @Query("update UserMfa m set m.lastUsedStep = :step where m.id = :id "
            + "and (m.lastUsedStep is null or m.lastUsedStep < :step)")
    int consumeStepIfNewer(@Param("id") UUID id, @Param("step") long step);

    void deleteByUserId(UUID userId);
}
