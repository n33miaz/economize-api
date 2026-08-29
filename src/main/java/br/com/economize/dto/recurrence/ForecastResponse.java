package br.com.economize.dto.recurrence;

import java.math.BigDecimal;
import java.util.List;

/**
 * Previsão de saldo período a período a partir das séries recorrentes ativas.
 *
 * <p>{@code startingBalance} ecoa o baseline informado pelo app (null quando não
 * veio): o sistema não tem saldo corrente consolidado, então o ponto de partida
 * do acumulado é responsabilidade de quem chama.
 *
 * <p>{@code anchorDay} (EC-116) é o dia do mês em que o ciclo do usuário vira,
 * lido pelo servidor a partir da janela pedida e usado para encadear os períodos
 * seguintes ao primeiro; vale 1 quando a projeção correu por mês do calendário,
 * que é o ciclo que abre todo dia 1. Ele está na resposta porque é a única parte
 * do recorte que o cliente NÃO mandou escrita: sem ele, uma leitura errada da
 * âncora (o 31 virar 28 depois de fevereiro) só apareceria três períodos adiante,
 * como um deslocamento silencioso de datas.
 *
 * <p>O nome do campo {@code months} continua o mesmo por retrocompatibilidade —
 * ele lista os "meses do usuário", que no ciclo ancorado não são meses do
 * calendário.
 */
public record ForecastResponse(
        BigDecimal startingBalance,
        Integer anchorDay,
        List<ForecastMonthResponse> months
) {
}
