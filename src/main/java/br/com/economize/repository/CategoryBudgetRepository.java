package br.com.economize.repository;

import br.com.economize.model.CategoryBudget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryBudgetRepository extends JpaRepository<CategoryBudget, UUID> {

    List<CategoryBudget> findAllByUserId(UUID userId);

    /**
     * O dono é FILTRO da consulta, não checagem posterior: teto de outro
     * usuário responde igual a teto inexistente, para não servir de oráculo.
     */
    Optional<CategoryBudget> findByUserIdAndCategoryId(UUID userId, UUID categoryId);

    void deleteByUserIdAndCategoryId(UUID userId, UUID categoryId);
}
