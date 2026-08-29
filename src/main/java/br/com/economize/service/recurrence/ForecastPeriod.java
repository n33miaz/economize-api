package br.com.economize.service.recurrence;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Um período da previsão de saldo — o "mês" DO USUÁRIO, com início e fim
 * explícitos e ambos INCLUSIVOS (EC-116).
 *
 * <p>O primeiro período é o recorte que o cliente pediu (a mesma janela do
 * EC-092: mês do calendário ou {@code start}/{@code end}); os seguintes são
 * gerados aqui, encadeados, porque a previsão pede N períodos futuros e só o
 * servidor sabe onde cada um termina. A regra do encadeamento é deliberadamente
 * idêntica à do app ({@code src/utils/cycleWindow.ts}): o ciclo pertence ao mês
 * em que COMEÇA, abre no dia da âncora (encurtado ao tamanho do mês) e fecha na
 * VÉSPERA da âncora seguinte. Fechar no próprio dia da âncora contaria o salário
 * do dia 12 no ciclo que fecha e no que abre; encurtar a âncora 31 para o último
 * dia de fevereiro é a única saída que não abre buraco entre um ciclo e o
 * seguinte.
 *
 * <p>Fuso: tudo aqui é data pura em UTC, o mesmo relógio em que o extrato data o
 * lançamento. Recortar o ciclo em qualquer outro fuso deslocaria o dia da virada.
 */
record ForecastPeriod(YearMonth month, LocalDate start, LocalDate end) {

    /**
     * O primeiro período é o recorte pedido, LITERAL. Não recalculamos a janela
     * a partir da âncora: a Home mostra "12/07 → 11/08" no card e é essa mesma
     * janela que vem na requisição — se o servidor "corrigisse" o recorte, as
     * duas réguas voltariam, agora com o servidor discordando do card em vez do
     * card discordando de si mesmo.
     */
    static ForecastPeriod first(LocalDate start, LocalDate end) {
        return new ForecastPeriod(YearMonth.from(start), start, end);
    }

    /**
     * O período seguinte: abre no dia seguinte ao fechamento do anterior (os
     * períodos são contíguos, sem dia órfão nem dia contado duas vezes) e fecha
     * na véspera da âncora do mês seguinte.
     */
    static ForecastPeriod next(ForecastPeriod previous, int anchorDay) {
        LocalDate start = previous.end().plusDays(1);
        YearMonth month = YearMonth.from(start);
        // a âncora do mês SEGUINTE está sempre depois de qualquer dia deste mês,
        // então o período resultante nunca é vazio nem invertido
        LocalDate nextStart = anchoredDay(month.plusMonths(1), anchorDay);
        return new ForecastPeriod(month, start, nextStart.minusDays(1));
    }

    /**
     * A âncora que a janela pedida carrega — o dia do mês em que o ciclo do
     * usuário vira. Ela não viaja como parâmetro próprio porque isso seria uma
     * SEGUNDA gramática para dizer o mesmo recorte que {@code start}/{@code end}
     * já diz; o número está dentro da janela e é daqui que sai.
     *
     * <p>É o MAIOR entre o dia da abertura e o dia da abertura seguinte, e o
     * maior justamente por causa do mês curto: o ciclo 31/01 → 27/02 abre no dia
     * 31 e o próximo abre no 28 (fevereiro encurtou), enquanto 28/02 → 30/03 abre
     * no 28 e o próximo no 31. Ler só uma das pontas degradaria a âncora 31 para
     * 28 na primeira passagem por fevereiro e ela nunca mais voltaria. O maior
     * acerta sempre porque o calendário nunca põe dois meses curtos em sequência
     * — fevereiro é ladeado por janeiro e março, ambos de 31 dias, e não existem
     * dois meses de 30 dias vizinhos —, então pelo menos uma das duas aberturas
     * cai no dia cheio da âncora.
     */
    static int anchorOf(LocalDate start, LocalDate end) {
        return Math.max(start.getDayOfMonth(), end.plusDays(1).getDayOfMonth());
    }

    /** O dia do mês, encurtado quando o mês não chega lá (31 em fevereiro). */
    static LocalDate anchoredDay(YearMonth month, int dayOfMonth) {
        return month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
    }

    boolean contains(LocalDate date) {
        return !date.isBefore(start) && !date.isAfter(end);
    }

    /**
     * Este período é exatamente um mês do calendário? Vale para o modo mês e
     * também para a âncora dia 1, em que ciclo e mês coincidem — a pergunta é
     * sobre o RECORTE resultante, não sobre o parâmetro que o gerou, para os dois
     * caminhos nunca divergirem num número.
     */
    boolean isCalendarMonth() {
        return start.equals(month.atDay(1)) && end.equals(month.atEndOfMonth());
    }

    /** Inclusivo nas duas pontas: 01/08 a 31/08 são 31 dias. */
    long lengthInDays() {
        return ChronoUnit.DAYS.between(start, end) + 1;
    }

    /**
     * A data em que o dia do mês informado cai DENTRO deste período, ou
     * {@code null} quando ele não cai — um ciclo de 28 dias que vai de 31/01 a
     * 27/02 simplesmente não contém nenhum dia 30. No mês do calendário sempre
     * existe resposta, e ela é a âncora encurtada ao tamanho do mês.
     */
    LocalDate dayWithin(int dayOfMonth) {
        YearMonth last = YearMonth.from(end);
        for (YearMonth candidateMonth = YearMonth.from(start);
             !candidateMonth.isAfter(last);
             candidateMonth = candidateMonth.plusMonths(1)) {
            LocalDate candidate = anchoredDay(candidateMonth, dayOfMonth);
            if (contains(candidate)) return candidate;
        }
        return null;
    }
}
