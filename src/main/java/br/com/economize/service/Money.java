package br.com.economize.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Dinheiro escrito como o Brasil escreve — para as frases que o usuário lê.
 *
 * <p><b>Por que isto existe.</b> Quase todo número do app é formatado na tela,
 * onde o {@code Intl} do JavaScript cuida do assunto. Mas algumas frases
 * nascem prontas aqui — o recado do vigia, o resumo do relatório — e ali o
 * {@code BigDecimal} concatenado direto produz o que ele produz: "R$ 39216.06",
 * com ponto no lugar da vírgula e sem separador de milhar, no meio de um app
 * inteiro em português.
 *
 * <p><b>E {@code stripTrailingZeros()} piora.</b> Ele transforma 1200.00 em
 * "1200" e 4.10 em "4.1" — some com os centavos em umas linhas e não em
 * outras, e a lista fica com valores de formatos diferentes um embaixo do
 * outro. Dinheiro em texto tem duas casas, sempre.
 */
public final class Money {

    private static final Locale PT_BR = new Locale("pt", "BR");

    private Money() {
    }

    /** "R$ 4.400,00" — com o separador que o Brasil usa, e sempre duas casas. */
    public static String brl(BigDecimal value) {
        return NumberFormat.getCurrencyInstance(PT_BR)
                .format(value.setScale(2, RoundingMode.HALF_UP));
    }
}
