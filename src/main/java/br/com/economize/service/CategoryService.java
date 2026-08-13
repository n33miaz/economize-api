package br.com.economize.service;

import br.com.economize.dto.category.CreateCategoryRequest;
import br.com.economize.dto.category.UpdateCategoryRequest;
import br.com.economize.model.Category;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.CategoryRuleRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$");

    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final UserRepository userRepository;

    public List<Category> listVisible(String email) {
        User user = requireUser(email);
        return categoryRepository.findVisibleTo(user.getId());
    }

    public Category create(String email, CreateCategoryRequest request) {
        User user = requireUser(email);
        String slug = slugify(request.name());
        ensureSlugFree(user.getId(), slug);
        validateColor(request.color());

        Category parent = request.parentId() != null ? requireUsableParent(user, request.parentId()) : null;

        return categoryRepository.save(Category.builder()
                .user(user)
                .name(request.name().trim())
                .slug(slug)
                // subcategoria não carrega grupo: quem agrupa é o pai
                .groupName(parent != null ? null : trimToNull(request.groupName()))
                .flow(resolveFlow(request.flow(), parent))
                .color(trimToNull(request.color()))
                .icon(trimToNull(request.icon()))
                .parent(parent)
                .archived(false)
                .build());
    }

    public Category update(String email, UUID id, UpdateCategoryRequest request) {
        User user = requireUser(email);
        // só categorias do próprio usuário — as do sistema são imutáveis
        Category category = categoryRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Categoria não encontrada ou não editável"));

        if (request.name() != null && !request.name().isBlank()) {
            String slug = slugify(request.name());
            if (!slug.equals(category.getSlug())) {
                ensureSlugFree(user.getId(), slug);
                category.setSlug(slug);
            }
            category.setName(request.name().trim());
        }
        if (Boolean.TRUE.equals(request.clearParent())) {
            category.setParent(null);
        } else if (request.parentId() != null) {
            if (categoryRepository.existsByParentId(category.getId())) {
                throw new IllegalArgumentException(
                        "\"" + category.getName() + "\" já tem subcategorias e por isso não pode virar subcategoria");
            }
            if (request.parentId().equals(category.getId())) {
                throw new IllegalArgumentException("Uma categoria não pode ser subcategoria dela mesma");
            }
            category.setParent(requireUsableParent(user, request.parentId()));
        }

        if (request.groupName() != null) {
            category.setGroupName(category.isRoot() ? trimToNull(request.groupName()) : null);
        } else if (!category.isRoot()) {
            // virou subcategoria agora: quem agrupa passa a ser o pai
            category.setGroupName(null);
        }
        if (request.flow() != null) category.setFlow(request.flow());
        if (request.color() != null) {
            validateColor(request.color());
            category.setColor(trimToNull(request.color()));
        }
        if (request.icon() != null) category.setIcon(trimToNull(request.icon()));
        if (request.archived() != null) {
            category.setArchived(request.archived());
            // arquivar o pai sem os filhos deixaria subcategoria órfã aparecendo
            // sozinha no picker; reativar é explícito, uma a uma
            if (request.archived()) archiveChildren(category.getId());
        }

        categoryRepository.save(category);
        // devolve a instância que veio da consulta: ela é a única com o pai já
        // carregado, e a resposta precisa do nome dele (o merge devolve outra,
        // com proxy morto — a sessão já fechou quando o DTO é montado)
        return category;
    }

    /**
     * Exclui de verdade quando nada referencia a categoria; caso contrário arquiva —
     * as transações históricas não podem ficar apontando para o nada.
     */
    public boolean deleteOrArchive(String email, UUID id) {
        User user = requireUser(email);
        Category category = categoryRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Categoria não encontrada ou não removível"));

        List<Category> children = categoryRepository.findAllByParentId(id);
        // o pai só sai de vez se ele E toda a descendência estiverem livres
        boolean referenced = isReferenced(user.getId(), id)
                || children.stream().anyMatch(child -> isReferenced(user.getId(), child.getId()));
        if (referenced) {
            children.forEach(child -> child.setArchived(true));
            categoryRepository.saveAll(children);
            category.setArchived(true);
            categoryRepository.save(category);
            return false;
        }
        categoryRepository.deleteAll(children);
        categoryRepository.delete(category);
        return true;
    }

    private boolean isReferenced(UUID userId, UUID categoryId) {
        return bankTransactionRepository.existsByUserIdAndCategoryId(userId, categoryId)
                || !categoryRuleRepository.findAllByUserIdAndCategoryId(userId, categoryId).isEmpty();
    }

    private void archiveChildren(UUID parentId) {
        List<Category> children = categoryRepository.findAllByParentId(parentId);
        if (children.isEmpty()) return;
        children.forEach(child -> child.setArchived(true));
        categoryRepository.saveAll(children);
    }

    /**
     * O pai precisa existir, ser visível ao usuário e ser raiz — é aqui que os dois
     * níveis param de crescer.
     */
    private Category requireUsableParent(User user, UUID parentId) {
        Category parent = categoryRepository.findAccessible(parentId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Categoria pai não encontrada"));
        if (!parent.isRoot()) {
            throw new IllegalArgumentException(
                    "\"" + parent.getName() + "\" já é uma subcategoria — a hierarquia tem no máximo dois níveis");
        }
        if (parent.isArchived()) {
            throw new IllegalArgumentException("Não dá para criar subcategoria em uma categoria arquivada");
        }
        return parent;
    }

    // Sem escolha explícita, a subcategoria herda o tipo do pai (uma sub de
    // "Receitas" nascer como gasto seria um erro silencioso na análise)
    private Category.Flow resolveFlow(Category.Flow requested, Category parent) {
        if (requested != null) return requested;
        if (parent != null) return parent.getFlow();
        return Category.Flow.EXPENSE;
    }

    private void ensureSlugFree(UUID userId, String slug) {
        boolean taken = categoryRepository.findBySlugAndUserId(slug, userId).isPresent()
                || categoryRepository.findBySlugAndUserIsNull(slug).isPresent();
        if (taken) {
            throw new IllegalArgumentException("Já existe uma categoria com esse nome");
        }
    }

    private void validateColor(String color) {
        if (color != null && !color.isBlank() && !HEX_COLOR.matcher(color.trim()).matches()) {
            throw new IllegalArgumentException("Cor deve ser um hex no formato #RRGGBB");
        }
    }

    static String slugify(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Nome da categoria é obrigatório");
        }
        String base = ACCENTS.matcher(Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFD))
                .replaceAll("");
        String slug = NON_SLUG.matcher(base).replaceAll("-").replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            throw new IllegalArgumentException("Nome da categoria precisa conter letras ou números");
        }
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }
}
