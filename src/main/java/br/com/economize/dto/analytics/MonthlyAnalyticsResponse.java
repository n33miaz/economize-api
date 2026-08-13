package br.com.economize.dto.analytics;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record MonthlyAnalyticsResponse(
        String month,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal net,
        MonthTotals previous,
        List<CategorySlice> categories,
        long pendingReviewCount
) {
    public record MonthTotals(
            String month,
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
