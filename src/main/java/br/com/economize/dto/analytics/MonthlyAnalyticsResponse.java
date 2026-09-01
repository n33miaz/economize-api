package br.com.economize.dto.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record MonthlyAnalyticsResponse(
        // rótulo YYYY-MM quando a consulta foi por mês; NULO na janela ancorada
        // (EC-092), que por definição não pertence a um mês do calendário
        String month,
        // sempre preenchidos, nos dois modos: são a única resposta honesta a
        // "qual período eu estou vendo?" e o app monta o cabeçalho com eles.
        // Datas inclusivas.
        LocalDate start,
        LocalDate end,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal net,
        MonthTotals previous,
        List<CategorySlice> categories,
        long pendingReviewCount,
        // EC-138: o que o total NÃO diz. Campo aditivo — o app publicado
        // ignora o que não conhece, então o contrato antigo segue valendo
        List<CycleCaveat> caveats
) {
    /**
     * Totais do período comparável — mês anterior do calendário no modo mês,
     * janela anterior do MESMO tamanho no modo janela.
     */
    public record MonthTotals(
            String month,
            LocalDate start,
            LocalDate end,
            BigDecimal totalIncome,
            BigDecimal totalExpense,
            BigDecimal net
    ) {
    }

    /**
     * Fatia da consolidação. No nível raiz os totais já vêm somados com os das
     * subcategorias, que ficam em {@code children} — a tela lê o pai e abre o
     * detalhe sem uma segunda chamada.
     */
    public record CategorySlice(
            UUID categoryId,
            String name,
            String groupName,
            String color,
            String icon,
            String systemKey,
            String parentSystemKey,
            boolean system,
            BigDecimal expenseTotal,
            BigDecimal incomeTotal,
            long txCount,
            BigDecimal previousExpenseTotal,
            BigDecimal expenseDeltaPct,
            List<CategorySlice> children
    ) {
    }
}
