package br.com.economize.repository;

import br.com.economize.model.Category;
import br.com.economize.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O catálogo de categorias rodando contra banco (EC-117).
 *
 * <p>Aqui moram duas consultas que <b>parecem</b> a mesma e decidem coisas
 * opostas: {@code findAccessible} (posso USAR: do sistema ou minha) e
 * {@code findByIdAndUserId} (posso EDITAR: só minha). Trocar uma pela outra não
 * quebra teste de serviço nenhum — e deixa o usuário editar a categoria do
 * sistema, que é compartilhada com todo mundo.
 */
@DataJpaTest
@DisplayName("CategoryRepository contra banco (EC-117)")
class CategoryRepositoryTest {

    @Autowired
    private CategoryRepository repository;

    @Autowired
    private UserRepository userRepository;

    private User dono;
    private User estranho;

    @BeforeEach
    void setUp() {
        dono = userRepository.save(user("dono@economize.test"));
        estranho = userRepository.save(user("estranho@economize.test"));
    }

    private User user(String email) {
        return User.builder()
                .name(email)
                .email(email)
                .password("nao-importa")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    /** {@code owner} nulo é categoria do sistema — é isso que a torna comum. */
    private Category categoria(User owner, String name, String slug, Category parent) {
        return repository.save(Category.builder()
                .user(owner)
                .name(name)
                .slug(slug)
                .groupName("Grupo")
                .parent(parent)
                .flow(Category.Flow.EXPENSE)
                .archived(false)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
    }

    // ------------------------------------------------------- o que eu VEJO

    @Test
    @DisplayName("O catálogo visível soma as do sistema com as minhas")
    void visivelSomaSistemaEMinhas() {
        categoria(null, "Alimentação", "alimentacao", null);
        categoria(dono, "Pet", "pet", null);
        categoria(estranho, "Barco", "barco", null);

        List<Category> visiveis = repository.findVisibleTo(dono.getId());

        // "Barco" é de outra pessoa: aparecer aqui já seria vazamento, e ainda
        // ofereceria ao usuário uma categoria que ele não pode editar
        assertThat(visiveis).extracting(Category::getName)
                .containsExactlyInAnyOrder("Alimentação", "Pet");
    }

    @Test
    @DisplayName("Cada pai vem imediatamente antes das próprias filhas")
    void ordemDaArvore() {
        Category alimentacao = categoria(null, "Alimentação", "alimentacao", null);
        categoria(null, "Delivery", "delivery", alimentacao);
        Category transporte = categoria(null, "Transporte", "transporte", null);
        categoria(null, "Combustível", "combustivel", transporte);

        List<Category> visiveis = repository.findVisibleTo(dono.getId());

        // A tela monta a árvore lendo a lista de cima para baixo: ordem errada
        // pendura a subcategoria no pai anterior. É o `order by` com coalesce e
        // `nulls last` que garante isso, e ele nunca havia sido executado
        assertThat(visiveis).extracting(Category::getName)
                .containsExactly("Alimentação", "Delivery", "Transporte", "Combustível");
    }

    @Test
    @DisplayName("Usuário sem categoria própria ainda vê as do sistema")
    void semCategoriaPropriaVeAsDoSistema() {
        categoria(null, "Alimentação", "alimentacao", null);

        assertThat(repository.findVisibleTo(estranho.getId())).hasSize(1);
    }

    // ------------------------------------------------------ o que eu USO

    @Test
    @DisplayName("Categoria do sistema é utilizável por qualquer usuário")
    void acessivelAlcancaAsDoSistema() {
        Category sistema = categoria(null, "Alimentação", "alimentacao", null);

        assertThat(repository.findAccessible(sistema.getId(), dono.getId())).isPresent();
        assertThat(repository.findAccessible(sistema.getId(), estranho.getId())).isPresent();
    }

    @Test
    @DisplayName("A categoria de outro usuário não é utilizável")
    void acessivelBarraAAlheia() {
        Category alheia = categoria(estranho, "Barco", "barco", null);

        // Sem isto, classificar uma transação com o id de uma categoria alheia
        // gravaria no extrato do dono uma categoria que ele nem enxerga
        assertThat(repository.findAccessible(alheia.getId(), dono.getId())).isEmpty();
    }

    // ---------------------------------------------------- o que eu EDITO

    @Test
    @DisplayName("A categoria do sistema não é editável por ninguém")
    void sistemaNaoEEditavel() {
        Category sistema = categoria(null, "Alimentação", "alimentacao", null);

        // É o que torna os seeds imutáveis: `c.user.id = :userId` nunca casa
        // com user NULL, então a rota de edição responde 404
        assertThat(repository.findByIdAndUserId(sistema.getId(), dono.getId())).isEmpty();
    }

    @Test
    @DisplayName("A minha categoria é editável; a do vizinho, não")
    void editavelSoAMinha() {
        Category minha = categoria(dono, "Pet", "pet", null);
        Category alheia = categoria(estranho, "Barco", "barco", null);

        assertThat(repository.findByIdAndUserId(minha.getId(), dono.getId())).isPresent();
        assertThat(repository.findByIdAndUserId(alheia.getId(), dono.getId())).isEmpty();
    }

    // ------------------------------------------------------------- o slug

    @Test
    @DisplayName("O mesmo slug pode existir no sistema e em cada usuário")
    void slugConviveEntreSistemaEUsuarios() {
        categoria(null, "Pet", "pet", null);
        categoria(dono, "Pet do dono", "pet", null);
        categoria(estranho, "Pet do vizinho", "pet", null);

        // Slug é identidade DENTRO do escopo. Único global impediria o usuário
        // de criar "Pet" só porque o sistema (ou um estranho) já tem uma
        assertThat(repository.findBySlugAndUserIsNull("pet"))
                .get().extracting(Category::getName).isEqualTo("Pet");
        assertThat(repository.findBySlugAndUserId("pet", dono.getId()))
                .get().extracting(Category::getName).isEqualTo("Pet do dono");
        assertThat(repository.findBySlugAndUserId("pet", estranho.getId()))
                .get().extracting(Category::getName).isEqualTo("Pet do vizinho");
    }

    @Test
    @DisplayName("A chave de sistema só casa com a categoria sem dono")
    void chaveDeSistemaIgnoraCopiaDoUsuario() {
        Category sistema = repository.save(Category.builder()
                .user(null).name("Alimentação").slug("alimentacao").flow(Category.Flow.EXPENSE)
                .systemKey("FOOD").archived(false)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
        repository.save(Category.builder()
                .user(dono).name("Alimentação minha").slug("alimentacao-minha")
                .flow(Category.Flow.EXPENSE).systemKey("FOOD").archived(false)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());

        // A chave é o contrato do motor de categorização com o seed: apontar
        // para a cópia de um usuário faria a regra do sistema mudar de dono
        assertThat(repository.findBySystemKeyAndUserIsNull("FOOD"))
                .get().extracting(Category::getId).isEqualTo(sistema.getId());
    }

    // -------------------------------------------------------- a hierarquia

    @Test
    @DisplayName("As filhas e a existência delas saem por pai")
    void filhasPorPai() {
        Category alimentacao = categoria(null, "Alimentação", "alimentacao", null);
        categoria(null, "Delivery", "delivery", alimentacao);
        Category pet = categoria(dono, "Pet", "pet", null);

        assertThat(repository.findAllByParentId(alimentacao.getId())).hasSize(1);
        // Existir filha é o que barra a exclusão do pai: falso aqui apagaria
        // a árvore inteira em cascata
        assertThat(repository.existsByParentId(alimentacao.getId())).isTrue();
        assertThat(repository.existsByParentId(pet.getId())).isFalse();
    }
}
