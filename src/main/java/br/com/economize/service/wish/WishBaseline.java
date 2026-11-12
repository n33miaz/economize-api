package br.com.economize.service.wish;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * O retrato financeiro do usuário que todo desejo consulta: quanto vale a hora
 * dele e quanto costuma sobrar no fim do ciclo.
 *
 * <p>É calculado UMA vez por usuário e reaproveitado por todos os desejos da
 * listagem — projetar dez desejos não pode custar sessenta consultas de janela.
 *
 * <p><b>Nulo aqui significa "não sei", nunca zero.</b> Um desejo que responde
 * "custa 0 horas" porque o perfil de jornada está vazio é pior do que um que
 * responde "me diga quantas horas você trabalha": o primeiro mente com
 * confiança. É por isso que os campos são objetos e não primitivos.
 *
 * @param workIncome      renda mensal de trabalho CONFIRMADA (salário e adiantamento)
 * @param hourlyRate      quanto vale uma hora de trabalho; nulo se falta renda ou jornada
 * @param hoursPerMonth   horas trabalhadas por mês, da jornada declarada
 * @param hoursPerDay     horas de um dia de expediente, para converter horas em dias
 * @param monthlyLeftover mediana do que sobrou nos ciclos completos recentes
 * @param monthlyExpense  mediana do que saiu nesses mesmos ciclos
 * @param cyclesConsidered quantos ciclos COM DADOS entraram na mediana
 * @param gaps            o que falta para o cálculo ficar completo (códigos para a tela)
 * @param lastClosedCycle sobra efetivamente apurada no último ciclo fechado COM
 *                        dados; não é a estimativa da mediana. Nulo se nenhum tem
 */
public record WishBaseline(
        BigDecimal workIncome,
        BigDecimal hourlyRate,
        BigDecimal hoursPerMonth,
        BigDecimal hoursPerDay,
        BigDecimal monthlyLeftover,
        BigDecimal monthlyExpense,
        int cyclesConsidered,
        List<String> gaps,
        ClosedCycle lastClosedCycle
) {
    /**
     * A medida de um ciclo concreto. O identificador é o mês em que o ciclo
     * começa (também para ciclo salarial), igual ao {@code cycleMonth} do aporte;
     * {@code start} e {@code end} são o período real, com o fim INCLUSIVO, para a
     * tela não ter de refazer o recorte do dia-âncora.
     */
    public record ClosedCycle(String cycleMonth, LocalDate start, LocalDate end, BigDecimal leftover) {
    }

    /** Mantém os chamadores antigos enquanto o ciclo fechado é acrescentado. */
    public WishBaseline(BigDecimal workIncome, BigDecimal hourlyRate, BigDecimal hoursPerMonth,
                        BigDecimal hoursPerDay, BigDecimal monthlyLeftover,
                        BigDecimal monthlyExpense, int cyclesConsidered, List<String> gaps) {
        this(workIncome, hourlyRate, hoursPerMonth, hoursPerDay, monthlyLeftover,
                monthlyExpense, cyclesConsidered, gaps, null);
    }
    /** Falta a jornada de trabalho (dias e horas por semana). */
    public static final String GAP_WORK_PROFILE = "WORK_PROFILE";
    /** Nenhuma fonte de renda de trabalho confirmada pelo usuário. */
    public static final String GAP_CONFIRMED_INCOME = "CONFIRMED_INCOME";
    /** Sem ciclo completo com transações — nada de onde tirar a sobra. */
    public static final String GAP_HISTORY = "HISTORY";
    /** Há histórico, mas o usuário não fecha o mês no azul. */
    public static final String GAP_NO_LEFTOVER = "NO_LEFTOVER";

    public boolean knowsHourlyRate() {
        return hourlyRate != null && hourlyRate.signum() > 0;
    }

    public boolean knowsLeftover() {
        return monthlyLeftover != null && monthlyLeftover.signum() > 0;
    }
}
