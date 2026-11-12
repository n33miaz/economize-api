package br.com.economize.repository;

import br.com.economize.model.Category;
import br.com.economize.model.CategoryRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRuleRepository extends JpaRepository<CategoryRule, UUID> {

    // join fetch: o motor percorre todas as regras do usuário por importação —
    // sem isso cada match viraria um SELECT extra de categoria
    @Query("select r from CategoryRule r join fetch r.category where r.user.id = :userId")
    List<CategoryRule> findAllWithCategoryByUserId(@Param("userId") UUID userId);

    /**
     * Soma acertos à regra direto no banco. Incremento atômico de propósito: a
     * importação trabalha com regras lidas antes da gravação, e salvar o total
     * que ela tem em mãos sobrescreveria acertos contados por outra gravação.
     * Roda dentro da transação de quem grava as linhas que acertaram.
     *
     * <p>{@code updatedAt} vai na mão: update em lote não dispara o
     * {@code @PreUpdate} da entidade.
     */
    @Modifying
    @Transactional
    @Query("update CategoryRule r set r.hits = r.hits + :count, r.lastHitAt = :at, "
            + "r.updatedAt = :at where r.id = :id")
    int addHits(@Param("id") UUID id, @Param("count") int count, @Param("at") OffsetDateTime at);

    /**
     * Reforço da revisão manual: a regra passa para a categoria que o usuário
     * escolheu e ganha um acerto, numa instrução só. Mesmo motivo do
     * {@link #addHits}: ler a regra, somar na cópia e salvar a entidade inteira
     * apagaria os acertos que uma sincronização gravasse entre a leitura e a
     * escrita. Origem e tipo de match ficam como estão, como sempre ficaram.
     *
     * <p>{@code updatedAt} vai na mão: update em lote não dispara o
     * {@code @PreUpdate} da entidade.
     */
    @Modifying
    @Transactional
    @Query("update CategoryRule r set r.category = :category, r.hits = r.hits + 1, "
            + "r.lastHitAt = :at, r.updatedAt = :at where r.id = :id")
    int reinforce(@Param("id") UUID id, @Param("category") Category category,
                  @Param("at") OffsetDateTime at);

    Optional<CategoryRule> findByUserIdAndPattern(UUID userId, String pattern);

    List<CategoryRule> findAllByUserIdAndCategoryId(UUID userId, UUID categoryId);
}
