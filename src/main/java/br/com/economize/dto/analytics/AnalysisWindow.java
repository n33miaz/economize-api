package br.com.economize.dto.analytics;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Janela de análise (EC-092). A tela "Para onde foi" deixou de ser presa ao mês
 * do calendário: o usuário ancora o ciclo no dia do salário (12/07 → 12/08) e a
 * API recebe a janela já calculada.
 *
 * <p>Duas formas de existir, e nunca as duas juntas: {@code month} (o contrato
 * antigo, que continua valendo palavra por palavra para o app já publicado) ou
 * o par {@code start}/{@code end}. Ambas as datas são INCLUSIVAS — 12/07 a
 * 12/08 são 32 dias, e o instante final exposto ao repositório é o começo do dia
 * seguinte ao fim, para não perder os lançamentos do último dia.
 *
 * <p>Fuso UTC porque é nele que os parsers gravam as datas do extrato (meia-noite
 * UTC); recortar a janela em qualquer outro deslocaria o dia de virada.
 */
public record AnalysisWindow(YearMonth month, LocalDate start, LocalDate end) {

    /**
     * Teto da janela. Um ano (366 para não punir ano bissexto) cobre o ciclo
     * ancorado, o trimestre e a retrospectiva anual — e limita o custo real da
     * consulta, que é o DOBRO disto: toda análise lê também a janela anterior
     * comparável. Acima disso a tela não é mais "para onde foi o mês", é
     * relatório histórico, que tem endpoint próprio.
     */
    public static final int MAX_DAYS = 366;

    public AnalysisWindow {
        if (start == null || end == null) {
            throw new IllegalArgumentException("Janela de análise exige data inicial e final");
        }
    }

    public static AnalysisWindow ofMonth(YearMonth month) {
        return new AnalysisWindow(month, month.atDay(1), month.atEndOfMonth());
    }

    public static AnalysisWindow of(LocalDate start, LocalDate end) {
        return new AnalysisWindow(null, start, end);
    }

    /**
     * Resolve os parâmetros crus da requisição. Devolve {@code null} quando nada
     * foi informado — cada endpoint tem um default diferente (a análise abre no
     * mês corrente, a listagem de transações sem filtro devolve tudo), e essa
     * decisão não é desta classe.
     *
     * <p>As datas chegam como texto e são parseadas aqui, não convertidas pelo
     * Spring: conversão de tipo em {@code @RequestParam} estoura
     * {@code ServerWebInputException}, que cairia no handler genérico como 500 —
     * o usuário mandando uma data torta merece 400 com ProblemDetail dizendo o
     * formato esperado.
     */
    public static AnalysisWindow resolve(String month, String start, String end) {
        boolean hasMonth = month != null && !month.isBlank();
        boolean hasStart = start != null && !start.isBlank();
        boolean hasEnd = end != null && !end.isBlank();

        if (hasMonth && (hasStart || hasEnd)) {
            // aceitar os dois exigiria eleger um vencedor silencioso, e o
            // usuário veria números de um período que não pediu
            throw new IllegalArgumentException(
                    "Informe mês OU janela (start e end), nunca os dois — são formas concorrentes "
                            + "de definir o mesmo período");
        }
        if (hasStart != hasEnd) {
            // janela pela metade não tem default honesto: "do dia 12 até quando?"
            throw new IllegalArgumentException("start e end devem ser informados juntos (datas ISO yyyy-MM-dd)");
        }
        if (hasMonth) {
            return ofMonth(parseMonth(month));
        }
        if (!hasStart) {
            return null;
        }

        LocalDate startDate = parseDate(start, "start");
        LocalDate endDate = parseDate(end, "end");
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("end não pode ser anterior a start");
        }
        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days > MAX_DAYS) {
            throw new IllegalArgumentException(
                    "Janela máxima de " + MAX_DAYS + " dias — a solicitada tem " + days);
        }
        return of(startDate, endDate);
    }

    /**
     * A janela anterior COMPARÁVEL, que é o que a variação da tela usa.
     *
     * <p>Para o ciclo ancorado ela tem exatamente o mesmo tamanho e termina na
     * véspera do início — 12/07→12/08 compara com 10/06→11/07. Fosse o mês
     * anterior do calendário, um ciclo de 32 dias seria comparado com fevereiro
     * (28) e a variação mentiria em ~15% sem que nada tivesse mudado no gasto.
     * A conta é em DIAS, não em meses, então a virada do mês (e a de fevereiro,
     * e a do ano) sai de graça.
     *
     * <p>No modo mês, o comparável continua sendo o mês anterior do calendário:
     * é o que o app já mostra e o que o usuário espera de "vs. mês passado".
     */
    public AnalysisWindow previous() {
        if (month != null) {
            return ofMonth(month.minusMonths(1));
        }
        long days = lengthInDays();
        return of(start.minusDays(days), start.minusDays(1));
    }

    public long lengthInDays() {
        return ChronoUnit.DAYS.between(start, end) + 1;
    }

    /** Rótulo do mês, ou {@code null} quando a janela é customizada. */
    public String monthLabel() {
        return month != null ? month.toString() : null;
    }

    public OffsetDateTime startInstant() {
        return start.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /** Exclusivo: o dia seguinte ao fim, porque {@code end} é inclusivo. */
    public OffsetDateTime endExclusiveInstant() {
        return end.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Mês inválido — use o formato YYYY-MM");
        }
    }

    private static LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " inválido — use o formato YYYY-MM-DD");
        }
    }
}
