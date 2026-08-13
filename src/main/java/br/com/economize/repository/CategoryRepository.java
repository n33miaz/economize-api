package br.com.economize.repository;

import br.com.economize.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    // Catálogo visível ao usuário: seeds do sistema (user NULL) + as dele.
    // Já sai na ordem da árvore — cada pai imediatamente antes dos filhos — e com
    // o pai buscado junto, porque serializar subcategoria sem ele estoura lazy.
    @Query("""
            select c from Category c
            left join fetch c.parent p
            where c.user is null or c.user.id = :userId
            order by coalesce(p.groupName, c.groupName) asc nulls last,
                     coalesce(p.name, c.name) asc,
                     case when c.parent is null then 0 else 1 end asc,
                     c.name asc
            """)
    List<Category> findVisibleTo(@Param("userId") UUID userId);

    // Categoria utilizável pelo usuário — do sistema ou dele; barra acesso a alheias
    @Query("""
            select c from Category c
            left join fetch c.parent
            where c.id = :id and (c.user is null or c.user.id = :userId)
            """)
    Optional<Category> findAccessible(@Param("id") UUID id, @Param("userId") UUID userId);

    // Só as do próprio usuário (seed tem user NULL e nunca casa) — é o que torna
    // as categorias do sistema imutáveis. O pai vem junto pelo mesmo motivo do
    // findVisibleTo: a resposta da edição mostra o caminho "Pai › Filha".
    @Query("""
            select c from Category c
            left join fetch c.parent
            where c.id = :id and c.user.id = :userId
            """)
    Optional<Category> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    Optional<Category> findBySystemKeyAndUserIsNull(String systemKey);

    Optional<Category> findBySlugAndUserId(String slug, UUID userId);

    Optional<Category> findBySlugAndUserIsNull(String slug);

    @Query("select c from Category c left join fetch c.parent where c.user is null")
    List<Category> findAllByUserIsNull();

    List<Category> findAllByParentId(UUID parentId);

    boolean existsByParentId(UUID parentId);
}
