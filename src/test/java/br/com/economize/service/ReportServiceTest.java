package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.Report;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.ReportRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.event.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O relatório gerado (EC-047).
 *
 * <p>O serviço não tinha teste nenhum, e o resumo que ele escrevia era a frase
 * MAIS visível da tela: aparecia no card antes de qualquer número. Estava
 * saindo como {@code "Período monthly: receitas R$ 4400.0000"} — enum em inglês
 * e quatro casas decimais do banco.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportService — o retrato e a frase que o usuário lê")
class ReportServiceTest {

    private static final String EMAIL = "dono@economize.test";
    private static final OffsetDateTime INICIO =
            OffsetDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime FIM =
            OffsetDateTime.of(2026, 7, 31, 23, 59, 59, 0, ZoneOffset.UTC);

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private ReportService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Dono").build();
    }

    private BankTransaction tx(String valor, String categoria, int dia) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(user)
                .transactionId("ext-" + UUID.randomUUID())
                .type(new BigDecimal(valor).signum() < 0 ? "DEBIT" : "CREDIT")
                .amount(new BigDecimal(valor))
                .category(categoria)
                .date(OffsetDateTime.of(2026, 7, dia, 12, 0, 0, 0, ZoneOffset.UTC))
                .build();
    }

    private Report gerar(List<BankTransaction> transacoes) {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        // só a janela é pedida ao banco; o limite superior anda um
        // microssegundo para o instante final continuar incluído
        when(bankTransactionRepository.findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                user.getId(), INICIO, FIM.plusNanos(1_000)))
                .thenReturn(transacoes);

        service.generate(EMAIL, Report.Period.MONTHLY, INICIO, FIM);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------ os números

    @Test
    @DisplayName("Entrada e saída saem do SINAL do valor")
    void totaisPeloSinal() {
        Report report = gerar(List.of(
                tx("4400.00", "SALARY", 10),
                tx("-3500.00", "FOOD", 15)));

        assertThat(report.getTotalIncome()).isEqualByComparingTo("4400.00");
        // A saída é guardada em módulo: é o que a tela mostra na coluna vermelha
        assertThat(report.getTotalExpense()).isEqualByComparingTo("3500.00");
    }

    @Test
    @DisplayName("Transação fora da janela não entra no retrato")
    void foraDaJanelaNaoEntra() {
        BankTransaction agosto = BankTransaction.builder()
                .id(UUID.randomUUID()).user(user).transactionId("ext-ago")
                .type("DEBIT").amount(new BigDecimal("-999.00")).category("FOOD")
                .date(OffsetDateTime.of(2026, 8, 2, 12, 0, 0, 0, ZoneOffset.UTC))
                .build();

        Report report = gerar(List.of(tx("-100.00", "FOOD", 15), agosto));

        assertThat(report.getTotalExpense()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("A quebra guarda entrada e saída SEPARADAS por categoria")
    void quebraSeparaEntradaESaida() {
        Report report = gerar(List.of(
                tx("4400.00", "SALARY", 10),
                tx("-300.00", "FOOD", 15),
                tx("-200.00", "FOOD", 20)));

        assertThat(report.getCategoriesJson())
                .contains("\"SALARY\":{\"income\":4400.00,\"expense\":0}")
                .contains("\"FOOD\":{\"income\":0,\"expense\":500.00}");
    }

    @Test
    @DisplayName("Categoria com entrada E saída não colapsa num líquido")
    void categoriaMistaNaoColapsa() {
        // Era o defeito: 4.400 de entrada e 5.830 de saída na mesma categoria
        // viravam "-1430", e a fatia da pizza contradizia o total de saídas do
        // próprio relatório na mesma tela
        Report report = gerar(List.of(
                tx("4400.00", "OTHER", 10),
                tx("-5830.00", "OTHER", 15)));

        assertThat(report.getCategoriesJson())
                .contains("\"income\":4400.00")
                .contains("\"expense\":5830.00")
                .doesNotContain("-1430");
    }

    @Test
    @DisplayName("A dominante é a de maior GASTO, não a de maior movimento")
    void dominanteEAMaiorDespesa() {
        Report report = gerar(List.of(
                tx("9000.00", "SALARY", 10),
                tx("-900.00", "HOUSING", 16)));

        // "No que foi meu dinheiro" nunca se responde com o salário
        assertThat(report.getDominantCategory()).isEqualTo("HOUSING");
    }

    @Test
    @DisplayName("Período sem saída nenhuma cai na maior entrada")
    void semSaidaDominanteEAEntrada() {
        Report report = gerar(List.of(tx("4400.00", "SALARY", 10)));

        assertThat(report.getDominantCategory()).isEqualTo("SALARY");
    }

    @Test
    @DisplayName("Sem categoria na transação, a chave é OTHER")
    void semCategoriaViraOther() {
        Report report = gerar(List.of(tx("-100.00", null, 15)));

        assertThat(report.getCategoriesJson()).contains("\"OTHER\"");
    }

    // -------------------------------------------------------------- a frase

    // `\h` e não `\s` nos padrões abaixo: o NumberFormat separa "R$" do
    // número com espaço NÃO separável, e em Java o `\s` não casa com ele
    @Test
    @DisplayName("Mês no azul: 'sobraram', com valor em real e duas casas")
    void resumoNoAzul() {
        Report report = gerar(List.of(
                tx("4400.00", "SALARY", 10),
                tx("-3500.00", "FOOD", 15)));

        assertThat(report.getSummary())
                .matches("Entraram R\\$\\h4\\.400,00 e saíram R\\$\\h3\\.500,00: sobraram R\\$\\h900,00\\.");
    }

    @Test
    @DisplayName("Mês no vermelho: 'faltaram', e o valor sem o sinal negativo")
    void resumoNoVermelho() {
        Report report = gerar(List.of(
                tx("4400.00", "SALARY", 10),
                tx("-5830.00", "FOOD", 15)));

        // "Faltaram R$ 1.430,00" e não "saldo -1430.0000": o que aconteceu é
        // que o dinheiro acabou antes
        assertThat(report.getSummary())
                .matches("Entraram R\\$\\h4\\.400,00 e saíram R\\$\\h5\\.830,00: faltaram R\\$\\h1\\.430,00\\.");
    }

    @Test
    @DisplayName("Empate tem frase própria — não é nem sobra nem falta")
    void resumoNoZero() {
        Report report = gerar(List.of(
                tx("1000.00", "SALARY", 10),
                tx("-1000.00", "FOOD", 15)));

        assertThat(report.getSummary()).contains("fechou no zero");
    }

    @Test
    @DisplayName("O resumo não imprime enum em inglês nem chave de sistema")
    void resumoNaoVazaJargao() {
        Report report = gerar(List.of(tx("-100.00", "OTHER", 15)));

        // Era o defeito exato: "Período monthly ... Categoria dominante: OTHER"
        assertThat(report.getSummary())
                .doesNotContain("monthly")
                .doesNotContain("MONTHLY")
                .doesNotContain("OTHER")
                // e nem as quatro casas decimais que vêm da coluna NUMERIC
                .doesNotContain("0000");
    }

    @Test
    @DisplayName("Gerar publica o evento de domínio")
    void gerarPublicaEvento() {
        gerar(List.of(tx("-100.00", "FOOD", 15)));

        verify(eventPublisher).publish(any());
    }

    // ------------------------------------------------- a regra da Análise

    @Test
    @DisplayName("Transferência interna, ignorada e estorno ficam fora; estorno parcial conta pelo líquido")
    void segueARegraDaAnalise() {
        BankTransaction interna = tx("-900.00", "TRANSFER", 11);
        interna.setInternalTransfer(true);
        BankTransaction ignorada = tx("-700.00", "TRANSFER", 12);
        ignorada.setIgnored(true);
        BankTransaction estornada = tx("-500.00", "TRANSFER", 13);
        estornada.setRefunded(true);
        BankTransaction devolucao = tx("500.00", "TRANSFER", 14);
        devolucao.setRefunded(true);
        BankTransaction parcial = tx("-604.91", "FOOD", 15);
        parcial.setRefundedAmount(new BigDecimal("18.50"));

        Report report = gerar(List.of(
                tx("4400.00", "SALARY", 10), interna, ignorada, estornada, devolucao, parcial));

        assertThat(report.getTotalIncome()).isEqualByComparingTo("4400.00");
        assertThat(report.getTotalExpense()).isEqualByComparingTo("586.41");
        // TRANSFER somaria 2.100 brutos e tomaria o lugar de FOOD
        assertThat(report.getDominantCategory()).isEqualTo("FOOD");
        assertThat(report.getCategoriesJson()).doesNotContain("TRANSFER").contains("586.41");
    }

    @Test
    @DisplayName("Compra estornada além do valor não vira receita nem despesa")
    void estornoMaiorQueACompraNaoViraReceita() {
        BankTransaction devolvidaAMais = tx("-100.00", "FOOD", 15);
        devolvidaAMais.setRefundedAmount(new BigDecimal("120.00"));

        Report report = gerar(List.of(devolvidaAMais));

        assertThat(report.getTotalIncome()).isEqualByComparingTo("0");
        assertThat(report.getTotalExpense()).isEqualByComparingTo("0");
    }

    // ------------------------------------------------- janela e categoria raiz

    @Test
    @DisplayName("Datas invertidas respondem 400 e não gravam relatório zerado")
    void datasInvertidas400() {
        assertThatThrownBy(() -> service.generate(EMAIL, Report.Period.MONTHLY, FIM, INICIO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("início");
        verifyNoInteractions(bankTransactionRepository, reportRepository);
    }

    @Test
    @DisplayName("Janela acima do teto responde 400 sem ler o histórico")
    void janelaLongaDemais400() {
        assertThatThrownBy(() -> service.generate(EMAIL, Report.Period.YEARLY,
                INICIO.minusYears(3), FIM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("366");
        verifyNoInteractions(bankTransactionRepository, reportRepository);
    }

    @Test
    @DisplayName("Subcategoria soma na raiz, como na Análise")
    void subcategoriaSomaNaRaiz() {
        Category alimentacao = Category.builder().id(UUID.randomUUID()).name("Alimentação")
                .slug("alimentacao").systemKey("FOOD").build();
        Category delivery = Category.builder().id(UUID.randomUUID()).name("Delivery")
                .slug("delivery").parent(alimentacao).build();
        Category moradia = Category.builder().id(UUID.randomUUID()).name("Moradia")
                .slug("moradia").systemKey("HOUSING").build();
        when(categoryRepository.findVisibleTo(user.getId())).thenReturn(List.of(alimentacao, delivery, moradia));

        BankTransaction mercado = tx("-300.00", "FOOD", 10);
        mercado.setCategoryId(alimentacao.getId());
        BankTransaction ifood1 = tx("-250.00", "delivery", 11);
        ifood1.setCategoryId(delivery.getId());
        BankTransaction ifood2 = tx("-250.00", "delivery", 12);
        ifood2.setCategoryId(delivery.getId());
        BankTransaction aluguel = tx("-700.00", "HOUSING", 5);
        aluguel.setCategoryId(moradia.getId());

        Report report = gerar(List.of(mercado, ifood1, ifood2, aluguel));

        // pelo campo legado, "delivery" (500) seria fatia própria e a dominante
        // seria Moradia (700); pela raiz, Alimentação soma 800 e domina
        assertThat(report.getCategoriesJson())
                .contains("\"FOOD\":{\"income\":0,\"expense\":800.00}")
                .doesNotContain("delivery");
        assertThat(report.getDominantCategory()).isEqualTo("FOOD");
    }

    @Test
    @DisplayName("Relatório inexistente ou de outro usuário é 404")
    void detalheInexistente404() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        UUID id = UUID.randomUUID();
        when(reportRepository.findByIdAndUserId(id, user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(EMAIL, id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(EMAIL, id)).isInstanceOf(ResourceNotFoundException.class);
        verify(reportRepository, never()).delete(any());
    }
}
