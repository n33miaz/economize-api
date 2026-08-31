package br.com.economize.dto.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Quanto do ciclo é dívida, e de que tipo (EC-139).
 *
 * <p>{@code shareOfExpense} vem nulo quando não houve despesa no período: 0/0
 * não é 0%, e mostrar "0% do seu mês é dívida" num mês sem extrato importado
 * seria uma boa notícia inventada.
 *
 * @param revolvingAlert há rotativo ou parcelamento de fatura no período — a
 *                       dívida mais cara do país, e a única que merece alarme
 *                       próprio na tela
 */
public record DebtOverviewResponse(
        String month,
        LocalDate start,
        LocalDate end,
        BigDecimal totalExpense,
        BigDecimal totalDebt,
        BigDecimal shareOfExpense,
        List<DebtGroup> groups,
        boolean revolvingAlert
) {

    public record DebtGroup(
            String kind,
            BigDecimal total,
            int count,
            List<DebtEntry> items
    ) {
    }

    /**
     * @param installment em qual parcela está, quando a descrição informa
     * @param total       de quantas
     * @param remaining   quantas faltam — o número que diz por quanto tempo
     *                    esse compromisso ainda vai pesar
     */
    public record DebtEntry(
            UUID transactionId,
            String description,
            BigDecimal amount,
            LocalDate date,
            Integer installment,
            Integer total,
            Integer remaining
    ) {
    }
}
