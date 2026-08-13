package br.com.economize.repository;

import br.com.economize.model.CategoryRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRuleRepository extends JpaRepository<CategoryRule, UUID> {

    // join fetch: o motor percorre todas as regras do usuário por importação —
    // sem isso cada match viraria um SELECT extra de categoria
    @Query("select r from CategoryRule r join fetch r.category where r.user.id = :userId")
    List<CategoryRule> findAllWithCategoryByUserId(@Param("userId") UUID userId);

    Optional<CategoryRule> findByUserIdAndPattern(UUID userId, String pattern);

    List<CategoryRule> findAllByUserIdAndCategoryId(UUID userId, UUID categoryId);
}
