package br.com.economize.repository;

import br.com.economize.model.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {

    /** O histórico da pessoa, do mais novo para o mais velho. */
    List<SupportTicket> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Dono como filtro: chamado de outra pessoa responde igual a inexistente. */
    Optional<SupportTicket> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Quantos chamados a pessoa tem em aberto.
     *
     * <p>É o que limita a abertura em série: não é regra de negócio, é rede
     * contra o toque repetido de quem está ansioso — e cinco chamados
     * idênticos atrasam a resposta de todo mundo, inclusive a dela.
     */
    long countByUserIdAndStatus(UUID userId, SupportTicket.Status status);
}
