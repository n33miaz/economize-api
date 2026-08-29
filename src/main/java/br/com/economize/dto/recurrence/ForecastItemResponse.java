package br.com.economize.dto.recurrence;

import br.com.economize.model.RecurringSeries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Uma série projetada dentro de um período da previsão.
 *
 * <p>{@code amount} é o valor considerado para o período (WEEKLY entra pelo
 * equivalente do período; no período corrente, o que já foi conciliado é
 * abatido). {@code settled=true} marca a série que já tem ocorrência real
 * vinculada no período corrente: o item aparece com o valor de referência, mas
 * fica FORA das somas — o período corrente projeta só o que falta.
 */
public record ForecastItemResponse(
        UUID seriesId,
        String displayName,
        RecurringSeries.Flow flow,
        // dia estimado do vencimento (âncora ajustada ao tamanho do mês);
        // null para WEEKLY, que não tem um dia único
        Integer dueDay,
        // EC-116: a data COMPLETA do vencimento. Num ciclo ancorado o dia
        // sozinho não ordena nem localiza nada — o ciclo 12/08→11/09 tem o dia
        // 20 (de agosto) ANTES do dia 5 (de setembro), e só a data diz qual é
        // qual. É também por ela que os itens são ordenados.
        LocalDate dueDate,
        BigDecimal amount,
        RecurringSeries.Source source,
        boolean settled
) {
}
