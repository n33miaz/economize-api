package br.com.economize.repository;

import br.com.economize.model.RecurringSeriesLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface RecurringSeriesLinkRepository extends JpaRepository<RecurringSeriesLink, UUID> {

    List<RecurringSeriesLink> findAllBySeriesIdIn(Collection<UUID> seriesIds);

    boolean existsBySeriesId(UUID seriesId);

    // Em lote e em JPQL, não pelo remove() entidade a entidade: a varredura
    // libera os vínculos de uma série órfã e no MESMO flush insere vínculos
    // novos para as mesmas transações. O Hibernate executa os INSERTs antes dos
    // DELETEs pendentes, e o UNIQUE de bank_transaction_id estouraria; o bulk
    // delete roda na hora em que é chamado, antes de qualquer insert.
    @Modifying
    @Query("delete from RecurringSeriesLink l where l.seriesId in :seriesIds")
    int deleteAllBySeriesIdIn(@Param("seriesIds") Collection<UUID> seriesIds);

    /** Contagem de ocorrências por série dentro do período. */
    interface SeriesOccurrences {
        UUID getSeriesId();

        long getOccurrences();
    }

    // Ocorrências conciliadas no período pela DATA DA TRANSAÇÃO, não pela data
    // do match: uma varredura atrasada não pode fazer a cobrança do mês passado
    // parecer liquidação do mês corrente. Insumo do "o que já caiu neste mês"
    // da previsão de saldo.
    @Query("""
            select l.seriesId as seriesId, count(l.id) as occurrences
            from RecurringSeriesLink l, BankTransaction t
            where t.id = l.bankTransactionId
              and l.seriesId in :seriesIds
              and t.date >= :from and t.date < :to
            group by l.seriesId
            """)
    List<SeriesOccurrences> countBySeriesIdInPeriod(@Param("seriesIds") Collection<UUID> seriesIds,
                                                    @Param("from") OffsetDateTime from,
                                                    @Param("to") OffsetDateTime to);
}
