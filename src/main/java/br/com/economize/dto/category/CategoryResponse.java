package br.com.economize.dto.category;

import br.com.economize.model.Category;

import java.util.UUID;

public record CategoryResponse(
        UUID id,
        String name,
        String slug,
        String groupName,
        Category.Flow flow,
        String color,
        String icon,
        String systemKey,
        UUID parentId,
        String parentName,
        // deixa o cliente resolver a cor da subcategoria pela do pai sem consultar a árvore
        String parentSystemKey,
        boolean system,
        boolean archived
) {
    public static CategoryResponse from(Category category) {
        Category parent = category.getParent();
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getGroupName(),
                category.getFlow(),
                category.getColor(),
                category.getIcon(),
                category.getSystemKey(),
                parent != null ? parent.getId() : null,
                parent != null ? parent.getName() : null,
                parent != null ? parent.getSystemKey() : null,
                category.getUser() == null,
                category.isArchived());
    }
}
