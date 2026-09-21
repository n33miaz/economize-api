package br.com.economize.repository;

import br.com.economize.model.ShoppingTrip;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * As idas ao mercado (V39).
 *
 * <p>Toda consulta de leitura recebe o par {@code (userId, groupId)} e devolve
 * o que é VISÍVEL para quem pergunta: as viagens dele e as que a casa dele
 * compartilhou. O filtro fica na cláusula, nunca em memória depois — o que
 * não é da pessoa não sai do banco. Quem não tem casa passa
 * {@link #NO_GROUP}, um UUID nulo que nenhuma viagem carrega: "ou
 * family_group_id = sentinela" é falso para todas as linhas, e a consulta
 * continua uma só.
 */
@Repository
public interface ShoppingTripRepository extends JpaRepository<ShoppingTrip, UUID> {

    UUID NO_GROUP = new UUID(0L, 0L);

    /** A viagem do PRÓPRIO usuário com este id de aparelho — a chave da idempotência. */
    Optional<ShoppingTrip> findByUserIdAndClientId(UUID userId, String clientId);

    /** A mesma nota não pode entrar duas vezes na conta da mesma pessoa. */
    Optional<ShoppingTrip> findByUserIdAndReceiptKey(UUID userId, String receiptKey);

    /**
     * A viagem que outro membro da casa compartilhou com este id de aparelho.
     * É por aqui que o PUT da esposa cai na viagem aberta pelo marido.
     */
    Optional<ShoppingTrip> findFirstByFamilyGroupIdAndClientIdOrderByCreatedAtAsc(UUID groupId, String clientId);

    @Query("""
            select t from ShoppingTrip t
            where (t.userId = :userId or t.familyGroupId = :groupId)
              and t.status in :statuses
            order by t.startedAt desc, t.createdAt desc
            """)
    List<ShoppingTrip> findVisible(@Param("userId") UUID userId,
                                   @Param("groupId") UUID groupId,
                                   @Param("statuses") Collection<ShoppingTrip.Status> statuses,
                                   Pageable pageable);

    @Query("""
            select count(t) from ShoppingTrip t
            where (t.userId = :userId or t.familyGroupId = :groupId)
              and t.status = :status
            """)
    long countVisibleByStatus(@Param("userId") UUID userId,
                              @Param("groupId") UUID groupId,
                              @Param("status") ShoppingTrip.Status status);

    /** As viagens encerradas, da mais recente para a mais antiga — a "última compra" da Home. */
    @Query("""
            select t from ShoppingTrip t
            where (t.userId = :userId or t.familyGroupId = :groupId)
              and t.status in :statuses
              and t.closedAt is not null
            order by t.closedAt desc
            """)
    List<ShoppingTrip> findVisibleClosed(@Param("userId") UUID userId,
                                         @Param("groupId") UUID groupId,
                                         @Param("statuses") Collection<ShoppingTrip.Status> statuses,
                                         Pageable pageable);

    /** As viagens encerradas dentro de uma janela — o "gasto no mercado este mês". */
    @Query("""
            select t from ShoppingTrip t
            where (t.userId = :userId or t.familyGroupId = :groupId)
              and t.status in :statuses
              and t.closedAt >= :start and t.closedAt < :end
            order by t.closedAt desc
            """)
    List<ShoppingTrip> findVisibleClosedInWindow(@Param("userId") UUID userId,
                                                 @Param("groupId") UUID groupId,
                                                 @Param("statuses") Collection<ShoppingTrip.Status> statuses,
                                                 @Param("start") OffsetDateTime start,
                                                 @Param("end") OffsetDateTime end);
}
