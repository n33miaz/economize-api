package br.com.economize.repository;

import br.com.economize.model.CardBill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CardBillRepository extends JpaRepository<CardBill, UUID> {

    /** Da mais recente para a mais antiga — é a ordem em que a tela lê. */
    List<CardBill> findByAccountIdOrderByClosingDateDesc(UUID accountId);

    /** O par que a re-sincronização usa para ATUALIZAR em vez de duplicar. */
    Optional<CardBill> findByAccountIdAndExternalId(UUID accountId, String externalId);

    /** Todas as faturas das contas dadas, para a tela montar de uma vez só. */
    List<CardBill> findByAccountIdIn(List<UUID> accountIds);
}
