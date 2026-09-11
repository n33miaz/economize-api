package br.com.economize.service.ai;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O que a IA sabe — e por que ela não podia saber o que sabia antes.
 *
 * <p><b>O defeito no concorrente.</b> No tour do Pierre, em 09/09/2026,
 * perguntei os gastos por categoria do mês. O chat respondeu <i>"Sem gastos
 * por categoria"</i> enquanto a home do mesmo aplicativo, no mesmo minuto,
 * mostrava <b>R$ 810,61 em cinco categorias</b>. Depois sugeriu conectar as
 * contas — para quem já tinha conectado cinco bancos. O assistente não estava
 * lendo o mesmo banco de dados que a tela.
 *
 * <p><b>O mesmo defeito, pior, na nossa casa.</b> O contexto que mandávamos:
 *
 * <ul>
 *   <li>somava <b>todas</b> as entradas e saídas <b>sem nenhuma das marcas</b>
 *       — sem tirar transferência entre contas do próprio dono, sem tirar
 *       duplicata ignorada, sem tirar par de estorno, sem tirar dinheiro que
 *       ficou dentro da casa. Medido no extrato real: só o movimento
 *       conta ↔ investimento são <b>R$ 39.216,06</b> que entravam nesse total
 *       (EC-214). O número que a IA recebia <b>não existe em nenhuma tela do
 *       app</b>, e qualquer resposta dela contradiz o painel por
 *       construção;</li>
 *   <li>mandava as somas de <b>toda a vida</b> sem dizer de que período eram,
 *       e depois 15 linhas soltas. Perguntar "quanto gastei em setembro"
 *       recebia de volta o total de dois anos;</li>
 *   <li>não mandava <b>categoria nenhuma</b>. É literalmente a razão de uma
 *       resposta "sem gastos por categoria" ser possível: não havia
 *       categoria no prompt;</li>
 *   <li>tinha um teto de 40 linhas de carteira declarado e <b>não usado</b> —
 *       o {@code forEach} percorria tudo, que é o bug que a constante existia
 *       para corrigir.</li>
 * </ul>
 *
 * <p><b>A regra do EC-200</b>, e a razão de esta classe existir separada do
 * serviço: <b>toda afirmação numérica tem de ter linha por trás</b>. O
 * contexto é montado de forma que cada número apareça ao lado dos lançamentos
 * que o compõem, e o prompt manda citar ou dizer que não encontrou. Um
 * assistente que só recebe totais só pode inventar a justificativa.
 */
public record AssistantContext(
        LocalDate windowStart,
        LocalDate windowEnd,
        BigDecimal income,
        BigDecimal expense,
        List<CategoryLine> categories,
        List<TransactionLine> lines,
        int totalInWindow,
        int excludedFromTotals,
        List<String> wallet,
        int walletTotal
) {

    /** Quanto uma categoria pesou, e quantas linhas a sustentam. */
    public record CategoryLine(String name, BigDecimal amount, long count) {
    }

    /** Uma linha citável: data, descrição, valor e categoria. */
    public record TransactionLine(LocalDate date, String description, BigDecimal amount,
                                  String category) {
    }

    /**
     * Quantas linhas do extrato entram no prompt.
     *
     * <p>Era 15, e 15 linhas não sustentam nenhum total: a IA recebia uma soma
     * de centenas de lançamentos e uma amostra de quinze, e não tinha como
     * justificar o número que ela mesma repetia. Oitenta é o que cabe num
     * prompt sem estourar a conta e é o suficiente para um mês inteiro do
     * extrato do dono (setembro tem ~60 linhas).
     */
    public static final int MAX_LINES = 80;

    /** Mesma razão do teto de linhas; este existia e não era aplicado. */
    public static final int MAX_WALLET_LINES = 40;

    /**
     * Monta o contexto a partir do que JÁ passou pelo filtro das telas.
     *
     * @param inWindow      lançamentos da janela, já sem as marcas (a mesma
     *                      cláusula que a Análise usa)
     * @param excluded      quantos ficaram de fora pelas marcas — vai no
     *                      prompt para a IA poder explicar a diferença em vez
     *                      de fingir que ela não existe
     * @param categoriesById nomes das categorias
     */
    public static AssistantContext of(LocalDate start, LocalDate end,
                                      List<BankTransaction> inWindow, int excluded,
                                      Map<UUID, Category> categoriesById,
                                      List<Transaction> walletTxs) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        Map<String, BigDecimal> porCategoria = new LinkedHashMap<>();
        Map<String, Long> contagem = new LinkedHashMap<>();

        for (BankTransaction tx : inWindow) {
            BigDecimal valor = tx.getAmount();
            if (valor.signum() >= 0) {
                income = income.add(valor);
                continue;
            }
            expense = expense.add(valor.abs());
            String nome = nomeDaCategoria(tx, categoriesById);
            porCategoria.merge(nome, valor.abs(), BigDecimal::add);
            contagem.merge(nome, 1L, Long::sum);
        }

        List<CategoryLine> categorias = new ArrayList<>();
        porCategoria.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .forEach(en -> categorias.add(
                        new CategoryLine(en.getKey(), en.getValue(), contagem.get(en.getKey()))));

        List<TransactionLine> linhas = inWindow.stream()
                .limit(MAX_LINES)
                .map(tx -> new TransactionLine(tx.getDate().toLocalDate(), tx.displayDescription(),
                        tx.getAmount(), nomeDaCategoria(tx, categoriesById)))
                .toList();

        List<String> carteira = walletTxs.stream()
                .limit(MAX_WALLET_LINES)
                .map(tx -> String.format("%s: %s cotas (preço médio R$ %s)",
                        tx.getAssetCode(), tx.getQuantity(), tx.getPriceAtTransaction()))
                .toList();

        return new AssistantContext(start, end, income, expense, categorias, linhas,
                inWindow.size(), excluded, carteira, walletTxs.size());
    }

    private static String nomeDaCategoria(BankTransaction tx, Map<UUID, Category> porId) {
        if (tx.getCategoryId() == null) return "Sem categoria";
        Category categoria = porId.get(tx.getCategoryId());
        return categoria != null ? categoria.getName() : "Sem categoria";
    }

    /**
     * O texto que vai no prompt.
     *
     * <p>Estrutura deliberada: primeiro o <b>período</b> (sem ele todo número é
     * ambíguo), depois os totais, depois as categorias que os compõem, depois
     * as linhas que compõem as categorias. Cada nível justifica o de cima —
     * é isso que permite a IA citar em vez de inventar.
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- PERÍODO ---\n");
        sb.append("De ").append(windowStart).append(" a ").append(windowEnd)
                .append(" (todos os números abaixo são DESTE período).\n\n");

        sb.append("--- TOTAIS DO PERÍODO ---\n");
        sb.append("Entradas: R$ ").append(income).append("\n");
        sb.append("Saídas: R$ ").append(expense).append("\n");
        sb.append("Lançamentos que entram nas somas: ").append(totalInWindow).append("\n");
        if (excludedFromTotals > 0) {
            sb.append("Lançamentos EXCLUÍDOS das somas: ").append(excludedFromTotals)
                    .append(" (transferência entre contas do próprio usuário, aplicação/resgate ")
                    .append("de investimento, par de estorno, duplicata descartada ou dinheiro ")
                    .append("que ficou dentro da casa). Eles existem no extrato e o saldo fecha ")
                    .append("com eles; o que estaria errado é somá-los.\n");
        }

        sb.append("\n--- GASTOS POR CATEGORIA (compõem o total de saídas) ---\n");
        if (categories.isEmpty()) {
            sb.append("Nenhuma saída neste período.\n");
        } else {
            for (CategoryLine categoria : categories) {
                sb.append(String.format("- %s: R$ %s (%d lançamento%s)%n",
                        categoria.name(), categoria.amount(), categoria.count(),
                        categoria.count() == 1 ? "" : "s"));
            }
        }

        sb.append("\n--- LANÇAMENTOS DO PERÍODO ---\n");
        if (lines.isEmpty()) {
            sb.append("Nenhum lançamento neste período.\n");
        } else {
            for (TransactionLine linha : lines) {
                sb.append(String.format("- %s | %s | R$ %s | %s%n",
                        linha.date(), linha.description(), linha.amount(), linha.category()));
            }
            if (totalInWindow > lines.size()) {
                sb.append("(").append(totalInWindow - lines.size())
                        .append(" lançamento(s) a mais não couberam nesta lista; os TOTAIS e as ")
                        .append("CATEGORIAS acima já incluem todos eles.)\n");
            }
        }

        sb.append("\n--- CARTEIRA DE INVESTIMENTOS ---\n");
        if (wallet.isEmpty()) {
            sb.append("O usuário não possui investimentos cadastrados no app.\n");
        } else {
            wallet.forEach(linha -> sb.append("- ").append(linha).append("\n"));
            if (walletTotal > wallet.size()) {
                sb.append("(").append(walletTotal - wallet.size())
                        .append(" posição(ões) a mais não listadas.)\n");
            }
        }
        return sb.toString();
    }
}
