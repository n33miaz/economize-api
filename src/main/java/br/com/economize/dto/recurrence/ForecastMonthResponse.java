package br.com.economize.dto.recurrence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Um período da previsão de saldo: somas do que ainda é esperado + itens.
 *
 * <p>{@code month} é o mês em que o período COMEÇA — no modo mês ele é o próprio
 * recorte; no ciclo ancorado ele é só a identidade do período (o ciclo que abre
 * em 12/08 é o "2026-08"), e quem descreve o recorte são {@code start}/{@code end}.
 * Renderizar "ago 2026" para um ciclo 12/08→11/09 é justamente a ambiguidade que
 * o EC-116 veio matar: em modo ciclo o rótulo se escreve com as datas.
 *
 * <p>{@code start}/{@code end} (EC-116) são INCLUSIVOS nas duas pontas e usam a
 * mesma gramática de janela do EC-092 — 12/08 a 11/09 são 31 dias. Vieram como
 * campos NOVOS: o app publicado ignora o que não conhece e continua lendo
 * {@code month}.
 *
 * <p>Não existe campo de rótulo, e a ausência é uma decisão: o rótulo é
 * apresentação (idioma, abreviação, "12/09 → 11/10" ou "de 12 de setembro a 11
 * de outubro" na leitura de tela) e o app já o escreve a partir destas mesmas
 * datas. Um rótulo vindo do servidor seria uma segunda fonte de verdade capaz de
 * discordar do chip que a tela renderiza ao lado. Com {@code start}/{@code end}
 * o cliente decide sem ambiguidade: recorte que começa no dia 1 e termina no
 * último dia do mês é mês do calendário e se escreve "set 2026"; qualquer outro
 * se escreve com as duas datas.
 */
public record ForecastMonthResponse(
        String month,
        // recorte explícito do período, para o app nunca ter que adivinhar o
        // que "2026-08" significa neste contexto
        LocalDate start,
        LocalDate end,
        BigDecimal expectedIncome,
        BigDecimal expectedExpense,
        BigDecimal expectedNet,
        // acumulado desde o período corrente, partindo do startingBalance
        // informado (ou de zero) — é o "saldo previsto" sem inventar saldo
        // consolidado
        BigDecimal cumulativeNet,
        List<ForecastItemResponse> items
) {
}
