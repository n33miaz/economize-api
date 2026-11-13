package br.com.economize.repository;

import br.com.economize.model.SweepRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SweepRunRepository extends JpaRepository<SweepRun, UUID> {

    List<SweepRun> findTop50ByUserIdOrderByRanAtDesc(UUID userId);

    /** O dono é FILTRO: passada de outro usuário responde igual a inexistente. */
    Optional<SweepRun> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Os ids que a passada tocou. Consulta nativa porque a tabela de itens não
     * tem entidade própria: ela existe só para o desfazer, e um {@code @Entity}
     * com chave composta para guardar dois UUIDs seria cerimônia sem uso.
     */
    @Query(value = "select transaction_id from sweep_run_items where run_id = :runId",
            nativeQuery = true)
    List<UUID> findItemIds(@Param("runId") UUID runId);

    /**
     * Insert simples, sem {@code ON CONFLICT}: a cláusula é do Postgres e o H2
     * dos testes não a entende — e ela não faz falta, porque quem chama passa
     * um conjunto, nunca uma lista com repetição.
     */
    @Modifying
    @Query(value = "insert into sweep_run_items (run_id, transaction_id) "
            + "values (:runId, :transactionId)",
            nativeQuery = true)
    void addItem(@Param("runId") UUID runId, @Param("transactionId") UUID transactionId);

    /**
     * Marca a passada como desfeita SÓ se ninguém marcou antes. Ler
     * {@code undoneAt == null} e depois salvar não é atômico: dois desfazer
     * simultâneos passavam os dois pela leitura e liberavam as linhas duas
     * vezes. O UPDATE condicional decide no banco quem venceu — devolve 1 para
     * o vencedor e 0 para o resto.
     */
    @Modifying
    @Query("update SweepRun r set r.undoneAt = :now "
            + "where r.id = :id and r.user.id = :userId and r.undoneAt is null")
    int markUndone(@Param("id") UUID id, @Param("userId") UUID userId,
                   @Param("now") java.time.OffsetDateTime now);

    default void addItems(UUID runId, Collection<UUID> transactionIds) {
        transactionIds.forEach(id -> addItem(runId, id));
    }
}
