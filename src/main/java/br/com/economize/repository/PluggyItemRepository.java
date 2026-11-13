package br.com.economize.repository;

import br.com.economize.model.PluggyItem;
import br.com.economize.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PluggyItemRepository extends JpaRepository<PluggyItem, UUID> {

    List<PluggyItem> findAllByUserIdOrderByCreatedAtAsc(UUID userId);

    // dono validado na mesma consulta: item de outro usuário responde 404,
    // nunca 403 — o id alheio não pode nem confirmar que existe
    Optional<PluggyItem> findByIdAndUserId(UUID id, UUID userId);

    Optional<PluggyItem> findByItemIdAndUserId(String itemId, UUID userId);

    boolean existsByItemId(String itemId);

    long countByUserId(UUID userId);

    // quem a sincronizacao automatica deve visitar: ao menos UMA conexao que
    // ainda pode ser lida. NOT_FOUND e NEEDS_USER_ACTION so voltam quando a
    // pessoa reconecta pelo app; status nulo e conexao nunca conferida
    @Query("""
            select distinct i.user from PluggyItem i
            where i.status is null or upper(i.status) not in ('NOT_FOUND', 'NEEDS_USER_ACTION')
            """)
    List<User> findUsersWithReadableItems();
}
