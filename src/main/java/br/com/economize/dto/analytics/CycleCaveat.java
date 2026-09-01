package br.com.economize.dto.analytics;

import java.math.BigDecimal;

/**
 * Uma ressalva sobre o período (EC-138): o que o total <b>não</b> diz.
 *
 * <p>Nota de rodapé, nunca número escondido — o valor continua sendo o que é; a
 * ressalva explica por que ele não é comparável.
 */
public record CycleCaveat(
        Kind kind,
        String title,
        String detail,
        /** Valor envolvido, quando existe um; nulo quando a ressalva é sobre o período em si. */
        BigDecimal amount
) {
    public enum Kind {
        /** Renda que caiu perto do fechamento — o gasto dela é do ciclo seguinte. */
        LATE_INCOME,
        /** O ciclo ainda está aberto: o total é parcial. */
        PARTIAL_PERIOD,
        /** Não há período anterior com movimento — a variação não significa nada. */
        NO_PREVIOUS_DATA
    }
}
