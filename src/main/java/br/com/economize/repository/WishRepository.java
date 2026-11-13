package br.com.economize.repository;

import br.com.economize.model.Wish;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WishRepository extends JpaRepository<Wish, UUID> {

    List<Wish> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Wish> findAllByUserIdAndStatusIn(UUID userId, List<Wish.Status> statuses);

    Optional<Wish> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Altera o saldo na própria linha. As guardas vivem no WHERE para que dois
     * aportes simultâneos se somem e nenhum deles consiga atravessar zero ou o
     * teto entre a leitura e a gravação.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Wish w set w.savedAmount = coalesce(w.savedAmount, 0) + :delta "
            + "where w.id = :id and w.user.id = :userId "
            + "and w.status not in :closedStatuses "
            + "and coalesce(w.savedAmount, 0) + :delta >= 0 "
            + "and coalesce(w.savedAmount, 0) + :delta <= :maximum")
    int adjustSavedAmount(@Param("id") UUID id,
                          @Param("userId") UUID userId,
                          @Param("delta") BigDecimal delta,
                          @Param("maximum") BigDecimal maximum,
                          @Param("closedStatuses") List<Wish.Status> closedStatuses);

    long countByUserId(UUID userId);
}
