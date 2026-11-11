package br.com.economize.repository;

import br.com.economize.model.IntegrationKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** As chaves de integração (V44). */
@Repository
public interface IntegrationKeyRepository extends JpaRepository<IntegrationKey, UUID> {

    /** A busca da autenticação: pelo hash, nunca pelo segredo. */
    Optional<IntegrationKey> findByKeyHash(String keyHash);

    /** O dono vem na cláusula: chave de outra pessoa simplesmente não existe. */
    Optional<IntegrationKey> findByIdAndUserId(UUID id, UUID userId);

    List<IntegrationKey> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserIdAndRevokedAtIsNull(UUID userId);

    /**
     * Marca o uso só se a marca gravada estiver velha. A condição fica no
     * UPDATE, e não só em memória, para que duas chamadas simultâneas não
     * escrevam as duas: a segunda encontra a marca nova e não toca na linha.
     */
    @Modifying
    @Query("""
            update IntegrationKey k set k.lastUsedAt = :now
            where k.id = :id and (k.lastUsedAt is null or k.lastUsedAt < :staleBefore)
            """)
    int touchLastUsed(@Param("id") UUID id,
                      @Param("now") OffsetDateTime now,
                      @Param("staleBefore") OffsetDateTime staleBefore);
}
