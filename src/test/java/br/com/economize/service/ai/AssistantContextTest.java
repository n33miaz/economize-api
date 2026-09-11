package br.com.economize.service.ai;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.Transaction;
import br.com.economize.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EC-200 — a IA vê o mesmo número que as telas, ou não vê número nenhum.
 *
 * <p>O defeito no concorrente, medido em 09/09/2026: perguntei os gastos por
 * categoria e o chat respondeu <i>"Sem gastos por categoria"</i> enquanto a
 * home do mesmo app, no mesmo minuto, mostrava R$ 810,61 em cinco categorias.
 * Depois sugeriu conectar as contas — para quem já tinha conectado cinco
 * bancos.
 *
 * <p>Aqui, o que se prova é a causa: um contexto que carrega o período, os
 * totais com as MESMAS exclusões das telas, as categorias que compõem os
 * totais e as linhas que compõem as categorias. Cada nível justifica o de
 * cima — é isso que permite citar em vez de inventar.
 */
class AssistantContextTest {

    private static final User DONO = User.builder()
            .id(UUID.randomUUID()).email("dono@economize.test").name("Dono").build();
    private static final LocalDate DE = LocalDate.parse("2026-06-12");
    private static final LocalDate ATE = LocalDate.parse("2026-09-10");

    private static final UUID MERCADO = UUID.randomUUID();
    private static final UUID TRANSPORTE = UUID.randomUUID();
    private static final Map<UUID, Category> CATEGORIAS = Map.of(
            MERCADO, Category.builder().id(MERCADO).name("Mercado").build(),
            TRANSPORTE, Category.builder().id(TRANSPORTE).name("Transporte").build());

    @Test
    @DisplayName("O período abre o contexto: soma sem período é número ambíguo")
    void periodoPrimeiro() {
        String texto = contexto(List.of(despesa("-50.00", "Padaria", MERCADO))).render();

        assertThat(texto).contains("--- PERÍODO ---");
        assertThat(texto).contains("De 2026-06-12 a 2026-09-10");
        assertThat(texto).contains("todos os números abaixo são DESTE período");
        // o período vem ANTES dos totais, e a ordem é a regra
        assertThat(texto.indexOf("--- PERÍODO ---"))
                .isLessThan(texto.indexOf("--- TOTAIS DO PERÍODO ---"));
    }

    @Test
    @DisplayName("Os gastos por categoria existem no prompt — a resposta do concorrente era impossível de dar certo")
    void categoriasNoPrompt() {
        // "Sem gastos por categoria" era inevitável: não havia categoria no
        // prompt para a IA ver
        String texto = contexto(List.of(
                despesa("-609.79", "SUPERMERCADO SERO", MERCADO),
                despesa("-291.90", "SUPERMERCADO SERO", MERCADO),
                despesa("-4.00", "Compra no débito Click M", TRANSPORTE))).render();

        assertThat(texto).contains("--- GASTOS POR CATEGORIA");
        assertThat(texto).contains("- Mercado: R$ 901.69 (2 lançamentos)");
        assertThat(texto).contains("- Transporte: R$ 4.00 (1 lançamento)");
    }

    @Test
    @DisplayName("A categoria mais pesada vem primeiro")
    void ordenadoPorPeso() {
        String texto = contexto(List.of(
                despesa("-4.00", "Click M", TRANSPORTE),
                despesa("-609.79", "SUPERMERCADO SERO", MERCADO))).render();

        assertThat(texto.indexOf("- Mercado:")).isLessThan(texto.indexOf("- Transporte:"));
    }

    @Test
    @DisplayName("Linha sem categoria é 'Sem categoria', não some da conta")
    void semCategoriaNaoSome() {
        String texto = contexto(List.of(despesa("-180.00", "SAQUE BANCO 24H", null))).render();

        assertThat(texto).contains("- Sem categoria: R$ 180.00 (1 lançamento)");
        assertThat(texto).contains("Saídas: R$ 180.00");
    }

    @Test
    @DisplayName("Cada total tem as linhas que o sustentam, embaixo dele")
    void totaisComAsLinhasQueOsSustentam() {
        String texto = contexto(List.of(despesa("-609.79", "SUPERMERCADO SERO", MERCADO))).render();

        assertThat(texto).contains("--- LANÇAMENTOS DO PERÍODO ---");
        assertThat(texto).contains("SUPERMERCADO SERO");
        assertThat(texto).contains("| Mercado");
        // totais → categorias → linhas, nessa ordem
        assertThat(texto.indexOf("--- GASTOS POR CATEGORIA"))
                .isLessThan(texto.indexOf("--- LANÇAMENTOS DO PERÍODO ---"));
    }

    @Test
    @DisplayName("O que ficou de fora das somas é DITO, não escondido")
    void oQueFicouDeForaEDito() {
        // Sem esta frase, a IA vê um extrato de 400 linhas, um total de 50 e
        // nenhuma forma de explicar a diferença sem inventar
        String texto = AssistantContext.of(DE, ATE,
                List.of(despesa("-50.00", "Padaria", MERCADO)), 350,
                CATEGORIAS, List.of()).render();

        assertThat(texto).contains("Lançamentos EXCLUÍDOS das somas: 350");
        assertThat(texto).contains("aplicação/resgate");
        assertThat(texto).contains("o que estaria errado é somá-los");
    }

    @Test
    @DisplayName("Sem exclusão nenhuma, a frase de exclusão não aparece")
    void semExclusaoNaoPoluiOPrompt() {
        String texto = contexto(List.of(despesa("-50.00", "Padaria", MERCADO))).render();

        assertThat(texto).doesNotContain("EXCLUÍDOS");
    }

    @Test
    @DisplayName("Quando as linhas não cabem, o prompt AVISA que os totais já as incluem")
    void listaTruncadaAvisa() {
        // É a diferença entre "a IA tem uma amostra" e "a IA acha que tem
        // tudo". Sem o aviso, ela soma as linhas que vê e contradiz o total
        List<BankTransaction> muitas = new java.util.ArrayList<>();
        for (int i = 0; i < AssistantContext.MAX_LINES + 20; i++) {
            muitas.add(despesa("-10.00", "Compra " + i, MERCADO));
        }

        AssistantContext contexto = AssistantContext.of(DE, ATE, muitas, 0, CATEGORIAS, List.of());
        String texto = contexto.render();

        assertThat(contexto.lines()).hasSize(AssistantContext.MAX_LINES);
        assertThat(texto).contains("20 lançamento(s) a mais não couberam");
        assertThat(texto).contains("os TOTAIS e as CATEGORIAS acima já incluem todos eles");
        // e o total continua sendo o de TODAS as linhas, não o das 80
        assertThat(texto).contains("Saídas: R$ 1000.00");
    }

    @Test
    @DisplayName("A carteira tem teto — a constante existia e não era aplicada")
    void carteiraComTeto() {
        List<Transaction> carteira = new java.util.ArrayList<>();
        for (int i = 0; i < AssistantContext.MAX_WALLET_LINES + 5; i++) {
            carteira.add(Transaction.builder()
                    .id(UUID.randomUUID())
                    .assetCode("ATIVO" + i)
                    .quantity(new BigDecimal("1"))
                    .priceAtTransaction(new BigDecimal("10.00"))
                    .build());
        }

        AssistantContext contexto = AssistantContext.of(DE, ATE, List.of(), 0, CATEGORIAS, carteira);

        assertThat(contexto.wallet()).hasSize(AssistantContext.MAX_WALLET_LINES);
        assertThat(contexto.render()).contains("5 posição(ões) a mais não listadas");
    }

    @Test
    @DisplayName("Período sem lançamento diz isso, em vez de deixar a IA adivinhar")
    void periodoVazioEDito() {
        String texto = AssistantContext.of(DE, ATE, List.of(), 0, CATEGORIAS, List.of()).render();

        assertThat(texto).contains("Nenhuma saída neste período.");
        assertThat(texto).contains("Nenhum lançamento neste período.");
        assertThat(texto).contains("O usuário não possui investimentos cadastrados no app.");
    }

    @Test
    @DisplayName("Entrada e saída não se misturam: crédito não vira categoria de gasto")
    void creditoNaoViraGasto() {
        String texto = contexto(List.of(
                credito("5343.53", "Salário recebido - Portabilidade"),
                despesa("-609.79", "SUPERMERCADO SERO", MERCADO))).render();

        assertThat(texto).contains("Entradas: R$ 5343.53");
        assertThat(texto).contains("Saídas: R$ 609.79");
        assertThat(texto).contains("- Mercado: R$ 609.79");
        // o salário não pode aparecer como gasto por categoria
        assertThat(texto).doesNotContain("- Sem categoria: R$ 5343.53");
    }

    // ------------------------------------------------------------------ apoio

    private AssistantContext contexto(List<BankTransaction> linhas) {
        return AssistantContext.of(DE, ATE, linhas, 0, CATEGORIAS, List.of());
    }

    private BankTransaction despesa(String valor, String descricao, UUID categoria) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(DONO)
                .type("DEBIT")
                .amount(new BigDecimal(valor))
                .description(descricao)
                .categoryId(categoria)
                .date(OffsetDateTime.parse("2026-08-29T10:08:00Z"))
                .build();
    }

    private BankTransaction credito(String valor, String descricao) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(DONO)
                .type("CREDIT")
                .amount(new BigDecimal(valor))
                .description(descricao)
                .date(OffsetDateTime.parse("2026-08-05T10:00:00Z"))
                .build();
    }
}
