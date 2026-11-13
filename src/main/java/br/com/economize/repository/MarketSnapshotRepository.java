package br.com.economize.repository;

import br.com.economize.model.MarketSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface MarketSnapshotRepository extends JpaRepository<MarketSnapshot, String> {

    /**
     * Apaga, num DELETE só, o que passou da idade máxima servida. Em JPQL e não
     * derivado ({@code deleteBy...}): o derivado carrega cada linha — com o JSON
     * inteiro — antes de apagar uma a uma, que é exatamente o custo que a limpeza
     * existe para tirar do boot.
     */
    @Modifying
    @Transactional
    @Query("delete from MarketSnapshot s where s.savedAt < :cutoff")
    int deleteSavedBefore(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * As linhas que a hidratação do boot ainda pode usar: listas de cotação
     * (fora o prefixo informado, que é o {@code data:}) gravadas depois do corte.
     * O prefixo vai como parâmetro, e não literal na query, porque o ":" de
     * {@code data:} seria lido como parâmetro nomeado.
     */
    @Query("select s from MarketSnapshot s where s.savedAt > :cutoff and s.key not like :excludedPattern")
    List<MarketSnapshot> findHydratable(@Param("cutoff") OffsetDateTime cutoff,
            @Param("excludedPattern") String excludedPattern);
}
