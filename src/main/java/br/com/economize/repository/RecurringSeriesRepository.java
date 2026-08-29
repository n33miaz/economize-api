package br.com.economize.repository;

import br.com.economize.model.RecurringSeries;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecurringSeriesRepository extends JpaRepository<RecurringSeries, UUID> {

    List<RecurringSeries> findAllByUserId(UUID userId);

    Optional<RecurringSeries> findByIdAndUserId(UUID id, UUID userId);

    // colisão de agendamento manual: a série é única por (usuário, chave, fluxo)
    Optional<RecurringSeries> findByUserIdAndMerchantKeyAndFlow(UUID userId, String merchantKey,
                                                                RecurringSeries.Flow flow);
}
