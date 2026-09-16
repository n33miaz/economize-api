package br.com.economize.service.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * O calendário de dias úteis BANCÁRIOS do Brasil.
 *
 * <p><b>Por que existe.</b> Salário não cai "dia 5": cai no 5º dia útil, e o
 * vale-refeição cai cinco dias úteis antes dele. Contar em dias do calendário
 * erra em toda semana que tem feriado — e erra justamente na pergunta que a
 * pessoa mais faz no fim do mês, "quando é que o dinheiro entra". Até aqui o
 * único ponto do sistema que sabia de dia útil pulava só sábado e domingo.
 *
 * <p><b>Por que "bancário", e não "legal".</b> Carnaval e Corpus Christi são
 * ponto facultativo, não feriado por lei — mas os bancos não operam, a folha
 * não é processada e o benefício não é creditado. O que interessa aqui é
 * quando o dinheiro CAI, então eles entram. O que fica de fora na v1: 31/12
 * (a maioria dos bancos fecha, mas não é regra) e os feriados estaduais e
 * municipais — o 5º dia útil de quem mora em São Paulo pode ficar um dia
 * adiantado em janeiro (25/01) e julho (09/07). A faixa earliest/latest da
 * previsão absorve esse dia.
 *
 * <p>20 de novembro entra só a partir de 2024 (Lei 14.759/2023): antes disso
 * era feriado municipal em algumas capitais, e tratar 2023 como se fosse
 * nacional mudaria a leitura de um histórico que já aconteceu.
 *
 * <p>Estática e sem Spring de propósito: é aritmética de calendário, e quem a
 * usa (a previsão de padrões, a recomendação de compra) precisa dela em
 * código puro e testável sem contexto.
 */
public final class BrazilianBusinessDays {

    /** 20/11 só conta como feriado nacional a partir deste ano. */
    static final int BLACK_AWARENESS_DAY_SINCE = 2024;

    private static final Map<Integer, Set<LocalDate>> CACHE = new ConcurrentHashMap<>();

    private BrazilianBusinessDays() {
    }

    /**
     * Domingo de Páscoa pelo algoritmo de Meeus/Jones/Butcher (gregoriano).
     * Verificado: 2026-04-05 e 2027-03-28.
     */
    static LocalDate easter(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }

    /** Os feriados bancários nacionais do ano, fixos e móveis. */
    public static Set<LocalDate> holidays(int year) {
        return CACHE.computeIfAbsent(year, BrazilianBusinessDays::buildHolidays);
    }

    private static Set<LocalDate> buildHolidays(int year) {
        Set<LocalDate> days = new HashSet<>();
        days.add(LocalDate.of(year, 1, 1));   // Confraternização Universal
        days.add(LocalDate.of(year, 4, 21));  // Tiradentes
        days.add(LocalDate.of(year, 5, 1));   // Dia do Trabalho
        days.add(LocalDate.of(year, 9, 7));   // Independência
        days.add(LocalDate.of(year, 10, 12)); // Nossa Senhora Aparecida
        days.add(LocalDate.of(year, 11, 2));  // Finados
        days.add(LocalDate.of(year, 11, 15)); // Proclamação da República
        if (year >= BLACK_AWARENESS_DAY_SINCE) {
            days.add(LocalDate.of(year, 11, 20)); // Consciência Negra
        }
        days.add(LocalDate.of(year, 12, 25)); // Natal

        LocalDate easter = easter(year);
        days.add(easter.minusDays(48)); // segunda de Carnaval
        days.add(easter.minusDays(47)); // terça de Carnaval
        days.add(easter.minusDays(2));  // Sexta-feira Santa
        days.add(easter.plusDays(60));  // Corpus Christi
        return Set.copyOf(days);
    }

    /** Segunda a sexta e fora da lista de feriados. */
    public static boolean isBusinessDay(LocalDate day) {
        DayOfWeek dow = day.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        return !holidays(day.getYear()).contains(day);
    }

    /** O próprio dia, se for útil; senão o primeiro útil depois dele. */
    public static LocalDate nextBusinessDay(LocalDate day) {
        LocalDate cursor = day;
        while (!isBusinessDay(cursor)) cursor = cursor.plusDays(1);
        return cursor;
    }

    /** O próprio dia, se for útil; senão o último útil antes dele. */
    public static LocalDate previousBusinessDay(LocalDate day) {
        LocalDate cursor = day;
        while (!isBusinessDay(cursor)) cursor = cursor.minusDays(1);
        return cursor;
    }

    /**
     * Posição do dia entre os dias úteis do mês, começando em 1. Zero quando o
     * dia não é útil — um PIX que caiu no sábado não tem "número de dia útil",
     * e inventar um (o da sexta? o da segunda?) esconderia exatamente o fato
     * que interessa.
     */
    public static int businessDayOfMonth(LocalDate day) {
        if (!isBusinessDay(day)) return 0;
        int count = 0;
        LocalDate cursor = day.withDayOfMonth(1);
        while (!cursor.isAfter(day)) {
            if (isBusinessDay(cursor)) count++;
            cursor = cursor.plusDays(1);
        }
        return count;
    }

    /**
     * Quantos dias úteis ainda existem no mês DEPOIS deste dia. Zero no último
     * dia útil. Para um dia não útil conta os que vêm depois dele — é o que
     * permite comparar "faltam 2 dias úteis" para qualquer data.
     */
    public static int businessDaysUntilMonthEnd(LocalDate day) {
        int count = 0;
        LocalDate cursor = day.plusDays(1);
        LocalDate end = YearMonth.from(day).atEndOfMonth();
        while (!cursor.isAfter(end)) {
            if (isBusinessDay(cursor)) count++;
            cursor = cursor.plusDays(1);
        }
        return count;
    }

    /**
     * O n-ésimo dia útil do mês (1 = primeiro). Um n maior do que o mês tem
     * cai no último dia útil, e não estoura: "23º dia útil" em fevereiro é a
     * mesma pergunta que "dia 31" — a resposta honesta é o fim do mês.
     */
    public static LocalDate nthBusinessDay(YearMonth month, int n) {
        if (n < 1) n = 1;
        int count = 0;
        LocalDate cursor = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        LocalDate last = null;
        while (!cursor.isAfter(end)) {
            if (isBusinessDay(cursor)) {
                count++;
                last = cursor;
                if (count == n) return cursor;
            }
            cursor = cursor.plusDays(1);
        }
        return last;
    }

    /**
     * Contado do fim: 0 é o último dia útil do mês, 1 a véspera dele, e assim
     * por diante. Espelha {@link #businessDaysUntilMonthEnd}, que é de onde a
     * medida sai quando o padrão observado é "cai perto do fim do mês".
     */
    public static LocalDate nthBusinessDayFromEnd(YearMonth month, int n) {
        if (n < 0) n = 0;
        int count = -1;
        LocalDate cursor = month.atEndOfMonth();
        LocalDate first = month.atDay(1);
        LocalDate earliest = null;
        while (!cursor.isBefore(first)) {
            if (isBusinessDay(cursor)) {
                count++;
                earliest = cursor;
                if (count == n) return cursor;
            }
            cursor = cursor.minusDays(1);
        }
        return earliest;
    }

    /**
     * Dias úteis de {@code from} (exclusivo) até {@code to} (inclusivo).
     * Negativo quando {@code to} vem antes — a função é simétrica, então
     * {@code businessDaysBetween(a, b) == -businessDaysBetween(b, a)}.
     */
    public static long businessDaysBetween(LocalDate from, LocalDate to) {
        if (from.equals(to)) return 0;
        if (to.isBefore(from)) return -businessDaysBetween(to, from);
        long count = 0;
        LocalDate cursor = from.plusDays(1);
        while (!cursor.isAfter(to)) {
            if (isBusinessDay(cursor)) count++;
            cursor = cursor.plusDays(1);
        }
        return count;
    }

    /**
     * Anda {@code n} dias úteis a partir de {@code day}; negativo volta. Zero
     * devolve o próprio dia, mesmo que ele não seja útil — quem quer
     * normalizar usa {@link #nextBusinessDay} ou {@link #previousBusinessDay}.
     */
    public static LocalDate plusBusinessDays(LocalDate day, int n) {
        LocalDate cursor = day;
        int step = n >= 0 ? 1 : -1;
        int remaining = Math.abs(n);
        while (remaining > 0) {
            cursor = cursor.plusDays(step);
            if (isBusinessDay(cursor)) remaining--;
        }
        return cursor;
    }
}
