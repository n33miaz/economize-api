package br.com.economize.service;

import br.com.economize.dto.category.CreateCategoryRequest;
import br.com.economize.dto.category.UpdateCategoryRequest;
import br.com.economize.model.Category;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.CategoryRuleRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    private static final String EMAIL = "ana@economize.dev";

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private CategoryRuleRepository categoryRuleRepository;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CategoryService service;

    private final User user = User.builder()
            .id(UUID.randomUUID()).name("Ana").email(EMAIL).password("x").build();

    private Category root(String name, Category.Flow flow) {
        return Category.builder().id(UUID.randomUUID()).name(name)
                .slug(CategoryService.slugify(name)).flow(flow).archived(false).build();
    }

    @Test
    void slugifyStripsAccentsAndSymbols() {
        assertThat(CategoryService.slugify("Educação & Cursos")).isEqualTo("educacao-cursos");
    }

    @Test
    void createRejectsNameCollidingWithSystemSeedSlug() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(categoryRepository.findBySlugAndUserId("alimentacao", user.getId())).thenReturn(Optional.empty());
        when(categoryRepository.findBySlugAndUserIsNull("alimentacao")).thenReturn(Optional.of(
                Category.builder().id(UUID.randomUUID()).name("Alimentação").slug("alimentacao")
                        .systemKey("FOOD").flow(Category.Flow.EXPENSE).archived(false).build()));

        assertThatThrownBy(() -> service.create(EMAIL,
                new CreateCategoryRequest("Alimentação", null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Já existe uma categoria com esse nome");
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void createDefaultsFlowToExpenseAndUnarchived() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(categoryRepository.findBySlugAndUserId("viagens", user.getId())).thenReturn(Optional.empty());
        when(categoryRepository.findBySlugAndUserIsNull("viagens")).thenReturn(Optional.empty());
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        Category created = service.create(EMAIL, new CreateCategoryRequest("Viagens", null, null, null, null, null));

        assertThat(created.getName()).isEqualTo("Viagens");
        assertThat(created.getSlug()).isEqualTo("viagens");
        assertThat(created.getFlow()).isEqualTo(Category.Flow.EXPENSE);
        assertThat(created.isArchived()).isFalse();
        assertThat(created.isRoot()).isTrue();
        assertThat(created.getUser()).isSameAs(user);
    }

    @Test
    void createUnderParentInheritsFlowAndDropsGroupName() {
        Category receitas = root("Receitas", Category.Flow.INCOME);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(categoryRepository.findBySlugAndUserId("aluguel-do-kitnet", user.getId())).thenReturn(Optional.empty());
        when(categoryRepository.findBySlugAndUserIsNull("aluguel-do-kitnet")).thenReturn(Optional.empty());
        when(categoryRepository.findAccessible(receitas.getId(), user.getId())).thenReturn(Optional.of(receitas));
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        Category created = service.create(EMAIL, new CreateCategoryRequest(
                "Aluguel do kitnet", "Movimentações", null, null, null, receitas.getId()));

        assertThat(created.getParent()).isSameAs(receitas);
        // sem flow explícito a sub herda o do pai — nascer EXPENSE sob "Receitas"
        // inverteria o sinal dela na análise
        assertThat(created.getFlow()).isEqualTo(Category.Flow.INCOME);
        // quem agrupa é o pai
        assertThat(created.getGroupName()).isNull();
    }

    @Test
    void createRejectsParentThatIsItselfASubcategory() {
        Category alimentacao = root("Alimentação", Category.Flow.EXPENSE);
        Category delivery = root("Delivery", Category.Flow.EXPENSE);
        delivery.setParent(alimentacao);

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(categoryRepository.findBySlugAndUserId("ifood", user.getId())).thenReturn(Optional.empty());
        when(categoryRepository.findBySlugAndUserIsNull("ifood")).thenReturn(Optional.empty());
        when(categoryRepository.findAccessible(delivery.getId(), user.getId())).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> service.create(EMAIL,
                new CreateCategoryRequest("iFood", null, null, null, null, delivery.getId())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no máximo dois níveis");
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void updateRejectsTurningACategoryWithChildrenIntoASubcategory() {
        Category mercado = Category.builder().id(UUID.randomUUID()).user(user)
                .name("Mercado").slug("mercado").flow(Category.Flow.EXPENSE).archived(false).build();
        Category alimentacao = root("Alimentação", Category.Flow.EXPENSE);

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(categoryRepository.findByIdAndUserId(mercado.getId(), user.getId())).thenReturn(Optional.of(mercado));
        when(categoryRepository.existsByParentId(mercado.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.update(EMAIL, mercado.getId(), new UpdateCategoryRequest(
                null, null, null, null, null, null, alimentacao.getId(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("já tem subcategorias");
    }

    @Test
    void archivingParentAlsoArchivesChildren() {
        Category parent = Category.builder().id(UUID.randomUUID()).user(user)
                .name("Casa").slug("casa").flow(Category.Flow.EXPENSE).archived(false).build();
        Category child = Category.builder().id(UUID.randomUUID()).user(user)
                .name("Faxina").slug("faxina").flow(Category.Flow.EXPENSE).archived(false).parent(parent).build();

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(categoryRepository.findByIdAndUserId(parent.getId(), user.getId())).thenReturn(Optional.of(parent));
        when(categoryRepository.findAllByParentId(parent.getId())).thenReturn(List.of(child));
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(EMAIL, parent.getId(), new UpdateCategoryRequest(
                null, null, null, null, null, true, null, null));

        assertThat(parent.isArchived()).isTrue();
        assertThat(child.isArchived()).isTrue();
    }

    @Test
    void updateSystemCategoryThrows() {
        UUID systemCategoryId = UUID.randomUUID();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        // seeds do sistema não têm dono, então a busca por id+userId volta vazia
        when(categoryRepository.findByIdAndUserId(systemCategoryId, user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(EMAIL, systemCategoryId,
                new UpdateCategoryRequest("Novo Nome", null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Categoria não encontrada ou não editável");
    }

    @Test
    void updateRenamesAndReslugifiesCheckingCollision() {
        Category existing = Category.builder()
                .id(UUID.randomUUID()).user(user).name("Mercado").slug("mercado")
                .flow(Category.Flow.EXPENSE).archived(false).build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(categoryRepository.findByIdAndUserId(existing.getId(), user.getId())).thenReturn(Optional.of(existing));
        when(categoryRepository.findBySlugAndUserId("feira-livre", user.getId())).thenReturn(Optional.empty());
        when(categoryRepository.findBySlugAndUserIsNull("feira-livre")).thenReturn(Optional.empty());
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        Category updated = service.update(EMAIL, existing.getId(),
                new UpdateCategoryRequest("Feira Livre", null, null, null, null, null, null, null));

        assertThat(updated.getName()).isEqualTo("Feira Livre");
        assertThat(updated.getSlug()).isEqualTo("feira-livre");
    }

    @Test
    void deleteOrArchiveArchivesWhenTransactionsReferenceCategory() {
        Category existing = Category.builder()
                .id(UUID.randomUUID()).user(user).name("Mercado").slug("mercado")
                .flow(Category.Flow.EXPENSE).archived(false).build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(categoryRepository.findByIdAndUserId(existing.getId(), user.getId())).thenReturn(Optional.of(existing));
        when(bankTransactionRepository.existsByUserIdAndCategoryId(user.getId(), existing.getId())).thenReturn(true);

        boolean deleted = service.deleteOrArchive(EMAIL, existing.getId());

        assertThat(deleted).isFalse();
        assertThat(existing.isArchived()).isTrue();
        verify(categoryRepository).save(existing);
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    void deleteOrArchiveArchivesTheWholeBranchWhenAChildIsReferenced() {
        Category parent = Category.builder().id(UUID.randomUUID()).user(user)
                .name("Casa").slug("casa").flow(Category.Flow.EXPENSE).archived(false).build();
        Category child = Category.builder().id(UUID.randomUUID()).user(user)
                .name("Faxina").slug("faxina").flow(Category.Flow.EXPENSE).archived(false).parent(parent).build();

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(categoryRepository.findByIdAndUserId(parent.getId(), user.getId())).thenReturn(Optional.of(parent));
        when(categoryRepository.findAllByParentId(parent.getId())).thenReturn(List.of(child));
        when(bankTransactionRepository.existsByUserIdAndCategoryId(user.getId(), parent.getId())).thenReturn(false);
        when(categoryRuleRepository.findAllByUserIdAndCategoryId(user.getId(), parent.getId())).thenReturn(List.of());
        when(bankTransactionRepository.existsByUserIdAndCategoryId(user.getId(), child.getId())).thenReturn(true);

        boolean deleted = service.deleteOrArchive(EMAIL, parent.getId());

        assertThat(deleted).isFalse();
        assertThat(parent.isArchived()).isTrue();
        assertThat(child.isArchived()).isTrue();
        verify(categoryRepository, never()).delete(any());
        verify(categoryRepository, never()).deleteAll(anyList());
    }

    @Test
    void deleteOrArchiveDeletesWhenNothingReferencesCategory() {
        Category existing = Category.builder()
                .id(UUID.randomUUID()).user(user).name("Mercado").slug("mercado")
                .flow(Category.Flow.EXPENSE).archived(false).build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(categoryRepository.findByIdAndUserId(existing.getId(), user.getId())).thenReturn(Optional.of(existing));
        when(bankTransactionRepository.existsByUserIdAndCategoryId(user.getId(), existing.getId())).thenReturn(false);
        when(categoryRuleRepository.findAllByUserIdAndCategoryId(user.getId(), existing.getId())).thenReturn(List.of());

        boolean deleted = service.deleteOrArchive(EMAIL, existing.getId());

        assertThat(deleted).isTrue();
        verify(categoryRepository).delete(existing);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void createRejectsInvalidColor() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(categoryRepository.findBySlugAndUserId("cinema", user.getId())).thenReturn(Optional.empty());
        when(categoryRepository.findBySlugAndUserIsNull("cinema")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(EMAIL,
                new CreateCategoryRequest("Cinema", null, null, "verde", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cor deve ser um hex");
        verify(categoryRepository, never()).save(any());
    }
}
