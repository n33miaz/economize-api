package br.com.economize.dto.category;

import br.com.economize.model.Category;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateCategoryRequest(
        @NotBlank @Size(max = 60) String name,
        @Size(max = 60) String groupName,
        Category.Flow flow,
        @Size(max = 9) String color,
        @Size(max = 40) String icon,
        // null = categoria raiz; preenchido = subcategoria de uma raiz existente
        UUID parentId
) {
}
