package br.com.economize.service.wish;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * O que o app responde sobre um desejo. Cada campo pode ser nulo, e nulo é uma
 * resposta legítima: "ainda não dá para saber" é honesto, um número inventado
 * não é.
 *
 * @param remaining        quanto falta juntar (alvo menos o já guardado)
 * @param hoursOfWork      o preço medido em horas de trabalho
 * @param workDays         as mesmas horas convertidas em dias de expediente
 * @param monthsToAfford   ciclos guardando a sobra até completar
 * @param estimatedDate    quando isso aconteceria, no ritmo de hoje
 * @param installments     em quantas vezes caberia usando SÓ a sobra
 * @param maxInstallment   o teto de parcela que a sobra aguenta
 * @param achieved         já juntou o suficiente
 * @param whatIfs          cenários de corte que antecipam a data
 */
public record WishProjection(
        BigDecimal remaining,
        BigDecimal hoursOfWork,
        BigDecimal workDays,
        Integer monthsToAfford,
        LocalDate estimatedDate,
        Integer installments,
        BigDecimal maxInstallment,
        boolean achieved,
        List<WhatIf> whatIfs
) {
    /**
     * "Cortando R$ X por mês, você chega N meses antes."
     *
     * <p>O corte é expresso em reais, não em porcentagem: ninguém corta 12% do
     * mês, mas todo mundo entende cortar R$ 150. A porcentagem fica como
     * rótulo de origem do cenário.
     */
    public record WhatIf(
            int percentOfExpense,
            BigDecimal monthlyCut,
            Integer months,
            LocalDate estimatedDate,
            Integer monthsEarlier
    ) {
    }
}
