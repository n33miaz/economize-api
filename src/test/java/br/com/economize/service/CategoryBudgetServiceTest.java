package br.com.economize.service;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.CategoryBudget;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryBudgetRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EC-204 — o teto por categoria, e as DUAS perguntas que ele responde.
 *
 * <p>"Você está em 20% do limite" é verdade e é inútil: 20% no terceiro dia é
 * ruim, no vigésimo oitavo é ótimo. Por isso o status responde "já estourou?"
 * <b>e</b> "o ritmo leva a estourar?", que são perguntas diferentes.
 */
@ExtendWith(MockitoExtension.class)
class CategoryBudgetServiceTest {

    private static final String EMAIL = "dono@economize.test";
    private static final UUID MERCADO = UUID.randomUUID();

    @Mock
    private CategoryBudgetRepository budgetRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CategoryBudgetService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Dono").password("x").build();
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        lenient().when(categoryRepository.findVisibleTo(user.getId())).thenReturn(
                List.of(Category.builder().id(MERCADO).name("Mercado").build()));
    }

    // ------------------------------------------------------------ definição

    @Test
    @DisplayName("Definir o teto duas vezes MUDA o mesmo, não cria um segundo")
    void upsertNaoDuplica() {
        CategoryBudget existente = teto("800.00");
        when(budgetRepository.findByUserIdAndCategoryId(user.getId(), MERCADO))
                .thenReturn(Optional.of(existente));

        service.set(EMAIL, MERCADO, new BigDecimal("900.00"));

        assertThat(existente.getMonthlyLimit()).isEqualByComparingTo("900.00");
        verify(budgetRepository).save(existente);
    }

    @Test
    @DisplayName("Teto zero ou negativo é recusado — não é teto")
    void tetoPrecisaSerPositivo() {
        assertThatThrownBy(() -> service.set(EMAIL, MERCADO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.set(EMAIL, MERCADO, new BigDecimal("-10")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(budgetRepository, never()).save(any());
    }

    @Test
    @DisplayName("Categoria de outro dono responde 'não encontrada', não 'proibida'")
    void categoriaAlheiaNaoConfirmaExistencia() {
        assertThatThrownBy(() -> service.set(EMAIL, UUID.randomUUID(), new BigDecimal("100")))
                .hasMessageContaining("não encontrada");
    }

    // ------------------------------------------------------------ avaliação

    @Test
    @DisplayName("Estourou: o excedente sai com o valor exato")
    void estourou() {
        comTeto("800.00");
        gastos(tx("-900.00", "2026-09-05"));

        CategoryBudgetService.Status status = service.statusFor(EMAIL, mesDeSetembro(),
                LocalDate.parse("2026-09-20"));

        assertThat(status.exceededCount()).isEqualTo(1);
        assertThat(status.lines()).singleElement().satisfies(linha -> {
            assertThat(linha.exceeded()).isTrue();
            assertThat(linha.overBy()).isEqualByComparingTo("100.00");
            assertThat(linha.abovePace()).isFalse();
        });
    }

    @Test
    @DisplayName("No ritmo de estourar é OUTRA coisa que ter estourado")
    void noRitmoDeEstourar() {
        // Teto 800 no mês; no dia 6 de 30, o esperado é ~160. Gastou 400
        comTeto("800.00");
        gastos(tx("-400.00", "2026-09-03"));

        CategoryBudgetService.Status status = service.statusFor(EMAIL, mesDeSetembro(),
                LocalDate.parse("2026-09-06"));

        assertThat(status.lines()).singleElement().satisfies(linha -> {
            assertThat(linha.exceeded()).isFalse();
            assertThat(linha.abovePace()).isTrue();
            assertThat(linha.overBy()).isEqualByComparingTo(BigDecimal.ZERO);
        });
        assertThat(status.abovePaceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Dentro do ritmo não alarma — o app não pode gritar no dia 2")
    void dentroDoRitmoNaoAlarma() {
        // Dia 6 de 30 com teto 800: esperado ~160. Gastou 150
        comTeto("800.00");
        gastos(tx("-150.00", "2026-09-03"));

        CategoryBudgetService.Status status = service.statusFor(EMAIL, mesDeSetembro(),
                LocalDate.parse("2026-09-06"));

        assertThat(status.exceededCount()).isZero();
        assertThat(status.abovePaceCount()).isZero();
    }

    @Test
    @DisplayName("Estourar por cinquenta centavos NÃO é notícia — o piso do EC-212")
    void estouroImaterialNaoAvisa() {
        comTeto("800.00");
        gastos(tx("-800.50", "2026-09-05"));

        CategoryBudgetService.Status status = service.statusFor(EMAIL, mesDeSetembro(),
                LocalDate.parse("2026-09-28"));

        assertThat(status.exceededCount()).isZero();
    }

    @Test
    @DisplayName("O teto MENSAL acompanha o tamanho do ciclo do usuário")
    void tetoAcompanhaOCiclo() {
        // Ciclo de 45 dias com teto mensal de 800: o teto da janela é 1.200,
        // e gastar 1.000 nele NÃO é estouro
        comTeto("800.00");
        gastos(tx("-1000.00", "2026-08-20"));

        CategoryBudgetService.Status status = service.statusFor(EMAIL,
                AnalysisWindow.of(LocalDate.parse("2026-08-12"), LocalDate.parse("2026-09-25")),
                LocalDate.parse("2026-09-25"));

        assertThat(status.lines()).singleElement().satisfies(linha -> {
            assertThat(linha.windowLimit()).isEqualByComparingTo("1200.00");
            assertThat(linha.exceeded()).isFalse();
        });
    }

    @Test
    @DisplayName("As mesmas exclusões de toda soma: aplicação e duplicata ficam de fora")
    void asMesmasExclusoes() {
        comTeto("800.00");
        BankTransaction aplicacao = tx("-500.00", "2026-09-05");
        aplicacao.setInternalTransfer(true);
        BankTransaction duplicata = tx("-500.00", "2026-09-05");
        duplicata.setIgnored(true);
        gastos(aplicacao, duplicata, tx("-100.00", "2026-09-05"));

        CategoryBudgetService.Status status = service.statusFor(EMAIL, mesDeSetembro(),
                LocalDate.parse("2026-09-28"));

        assertThat(status.lines()).singleElement()
                .extracting(CategoryBudgetService.Line::spent)
                .isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("Crédito na categoria não abate o teto — receita não é 'gastar menos'")
    void creditoNaoAbate() {
        comTeto("800.00");
        gastos(tx("-100.00", "2026-09-05"), tx("50.00", "2026-09-06"));

        assertThat(service.statusFor(EMAIL, mesDeSetembro(), LocalDate.parse("2026-09-28"))
                .lines().get(0).spent()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Sem teto nenhum, nem consulta o extrato")
    void semTetoNaoConsulta() {
        when(budgetRepository.findAllByUserId(user.getId())).thenReturn(List.of());

        CategoryBudgetService.Status status = service.statusFor(EMAIL, mesDeSetembro(),
                LocalDate.parse("2026-09-28"));

        assertThat(status.lines()).isEmpty();
        verify(bankTransactionRepository, never())
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        any(), any(), any());
    }

    @Test
    @DisplayName("Categoria com teto e sem gasto aparece zerada, não some")
    void semGastoAparece() {
        // Sumir seria a tela dizer que o teto não existe
        comTeto("800.00");
        gastos();

        assertThat(service.statusFor(EMAIL, mesDeSetembro(), LocalDate.parse("2026-09-10"))
                .lines()).singleElement()
                .extracting(CategoryBudgetService.Line::spent)
                .isEqualTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------ apoio

    private AnalysisWindow mesDeSetembro() {
        return AnalysisWindow.of(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"));
    }

    private CategoryBudget teto(String limite) {
        return CategoryBudget.builder()
                .id(UUID.randomUUID()).user(user).categoryId(MERCADO)
                .monthlyLimit(new BigDecimal(limite)).build();
    }

    private void comTeto(String limite) {
        when(budgetRepository.findAllByUserId(user.getId())).thenReturn(List.of(teto(limite)));
    }

    private void gastos(BankTransaction... transacoes) {
        when(bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        eq(user.getId()), any(), any()))
                .thenReturn(List.of(transacoes));
    }

    private BankTransaction tx(String valor, String data) {
        return BankTransaction.builder()
                .id(UUID.randomUUID()).user(user)
                .type(valor.startsWith("-") ? "DEBIT" : "CREDIT")
                .amount(new BigDecimal(valor))
                .description("compra")
                .categoryId(MERCADO)
                .date(OffsetDateTime.parse(data + "T12:00:00Z"))
                .build();
    }
}
