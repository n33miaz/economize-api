package br.com.economize.repository;

import br.com.economize.model.MfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, UUID> {

    /**
     * Os códigos ainda válidos. O código chega sem dono declarado — vem digitado
     * pelo usuário — e por isso é conferido contra os hashes DESTA conta, um a
     * um: bcrypt não permite buscar pelo hash.
     */
    List<MfaRecoveryCode> findAllByUserIdAndUsedAtIsNull(UUID userId);

    long countByUserIdAndUsedAtIsNull(UUID userId);

    /**
     * Risca o código somente se ele ainda não foi usado. O teste e a escrita
     * precisam ser uma única operação: duas requisições podem encontrar o
     * mesmo hash antes de qualquer uma delas salvar a entidade.
     */
    @Modifying
    @Transactional
    @Query("update MfaRecoveryCode c set c.usedAt = :now where c.id = :id and c.usedAt is null")
    int consumeIfUnused(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    void deleteAllByUserId(UUID userId);
}
