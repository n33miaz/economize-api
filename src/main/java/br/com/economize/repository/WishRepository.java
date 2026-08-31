package br.com.economize.repository;

import br.com.economize.model.Wish;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WishRepository extends JpaRepository<Wish, UUID> {

    List<Wish> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Wish> findAllByUserIdAndStatusIn(UUID userId, List<Wish.Status> statuses);

    Optional<Wish> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);
}
