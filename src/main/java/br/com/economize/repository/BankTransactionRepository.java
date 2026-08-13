package br.com.economize.repository;

import br.com.economize.model.BankTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {
    List<BankTransaction> findAllByUserIdOrderByDateDesc(UUID userId);

    boolean existsByUserIdAndTransactionId(UUID userId, String transactionId);

    List<BankTransaction> findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
            UUID userId, OffsetDateTime start, OffsetDateTime end);

    List<BankTransaction> findAllByUserIdAndUploadIdOrderByDateDesc(UUID userId, UUID uploadId);

    List<BankTransaction> findAllByUserIdAndReviewStatusInOrderByDateDesc(
            UUID userId, Collection<BankTransaction.ReviewStatus> statuses);

    List<BankTransaction> findAllByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    long countByUserIdAndReviewStatusIn(UUID userId, Collection<BankTransaction.ReviewStatus> statuses);

    boolean existsByUserIdAndCategoryId(UUID userId, UUID categoryId);

    // Agregação da análise mensal: soma por categoria e tipo dentro da janela.
    // JPQL puro para funcionar igual no Postgres e no H2 dos testes.
    @Query("""
            select t.categoryId as categoryId, t.type as type,
                   sum(t.amount) as total, count(t) as txCount
            from BankTransaction t
            where t.user.id = :userId and t.date >= :start and t.date < :end
            group by t.categoryId, t.type
            """)
    List<CategoryTotal> sumByCategory(@Param("userId") UUID userId,
                                      @Param("start") OffsetDateTime start,
                                      @Param("end") OffsetDateTime end);

    // Janela total de dados do usuário — o seletor de meses é derivado disso
    @Query("select min(t.date), max(t.date) from BankTransaction t where t.user.id = :userId")
    List<Object[]> findDateBounds(@Param("userId") UUID userId);

    interface CategoryTotal {
        UUID getCategoryId();

        String getType();

        java.math.BigDecimal getTotal();

        long getTxCount();
    }
}
