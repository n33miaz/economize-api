package br.com.economize.dto.category;

import br.com.economize.model.Category;
import jakarta.validation.constraints.Size;

import java.util.UUID;

// PATCH parcial: campo null = não alterar
public record UpdateCategoryRequest(
        @Size(max = 60) String name,
        @Size(max = 60) String groupName,
        Category.Flow flow,
        @Size(max = 9) String color,
        @Size(max = 40) String icon,
        Boolean archived,
        UUID parentId,
        // parentId sozinho não distingue "não mexer" de "promover para raiz"
        Boolean clearParent
) {
}
