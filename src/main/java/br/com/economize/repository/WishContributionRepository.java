package br.com.economize.repository;

import br.com.economize.model.WishContribution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WishContributionRepository extends JpaRepository<WishContribution, UUID> {

    /** O extrato da meta, do aporte mais novo para o mais velho. */
    List<WishContribution> findAllByWishIdOrderByCreatedAtDesc(UUID wishId);

    /**
     * O aporte daquele ciclo, se já houve.
     *
     * <p>É o que impede a sobra de setembro entrar duas vezes na mesma meta —
     * dinheiro que existiu uma vez só não pode dobrar o progresso porque
     * alguém tocou duas vezes no botão. O banco garante o mesmo por índice
     * único; esta consulta existe para o serviço responder com uma frase em
     * vez de deixar estourar uma violação de constraint.
     */
    Optional<WishContribution> findByWishIdAndCycleMonth(UUID wishId, String cycleMonth);

    /** Dono como filtro: aporte de outra pessoa responde igual a inexistente. */
    Optional<WishContribution> findByIdAndUserId(UUID id, UUID userId);
}
