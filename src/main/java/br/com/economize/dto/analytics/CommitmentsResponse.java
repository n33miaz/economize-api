package br.com.economize.dto.analytics;

import java.math.BigDecimal;
import java.util.List;

/**
 * Parcelas e compromissos dos próximos meses, contra a renda.
 *
 * <p>A pergunta é a do Painel de Dívidas que o Banco Central promete para 2027:
 * quanto do que entra por mês já está prometido antes de o mês começar. O
 * limite ({@code thresholdPct}, 40% por padrão) é uma <b>referência</b> de
 * mercado para "comprometido demais", não regra — a tela diz isso.
 *
 * @param thresholdPct    o limite usado no aviso, em pontos percentuais
 * @param income          a renda considerada e de onde ela veio
 * @param months          um item por mês, do corrente em diante
 * @param peakSharePct    o maior percentual da janela; nulo sem renda
 * @param aboveThreshold  algum mês passou do limite
 */
public record CommitmentsResponse(
        BigDecimal thresholdPct,
        CommitmentIncome income,
        List<CommitmentMonth> months,
        BigDecimal peakSharePct,
        boolean aboveThreshold
) {

    /**
     * @param amount  renda mensal considerada; nula quando não há como saber
     * @param origin  SOURCES (valor das fontes cadastradas), MEASURED (padrão
     *                medido no extrato para fontes cadastradas sem valor) ou NONE
     * @param sources quantas fontes entraram na soma
     */
    public record CommitmentIncome(
            BigDecimal amount,
            String origin,
            int sources
    ) {
    }

    /**
     * @param month          `YYYY-MM`
     * @param partial        mês corrente: conta só o que ainda vai cair a partir
     *                       de hoje, não o que já saiu
     * @param installments   parcelas de compras já feitas que caem no mês
     * @param debts          financiamento, empréstimo, consórcio e rotativo
     *                       recorrentes que vencem no mês
     * @param total          a soma das duas
     * @param shareOfIncome  total sobre a renda, em %; nulo sem renda
     * @param aboveThreshold o mês passou do limite
     */
    public record CommitmentMonth(
            String month,
            boolean partial,
            BigDecimal installments,
            BigDecimal debts,
            BigDecimal total,
            BigDecimal shareOfIncome,
            boolean aboveThreshold
    ) {
    }
}
