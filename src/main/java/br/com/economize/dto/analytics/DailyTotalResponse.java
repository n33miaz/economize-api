package br.com.economize.dto.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Quanto saiu e quanto entrou num dia — EC-235.
 *
 * <p>Existe para o calendário da primeira tela. Ele precisa de trinta números,
 * e a alternativa seria o app baixar o extrato inteiro para somá-los: medido
 * em campo, 1.688 linhas custam 92 KB e segundos de espera. Trinta linhas
 * destas custam menos de 2 KB.
 *
 * <p>Só os dias COM movimento voltam. Dia vazio é ausência, e mandar trinta
 * zeros para o app redesenhar a mesma coisa é pagar por nada — quem monta a
 * grade é a tela, que sabe quantos dias o mês tem.
 *
 * @param date   o dia, na data de lançamento que o extrato informa
 * @param spent  soma das saídas, em positivo
 * @param earned soma das entradas
 * @param count  quantos lançamentos entraram nas somas deste dia
 */
public record DailyTotalResponse(
        LocalDate date,
        BigDecimal spent,
        BigDecimal earned,
        long count
) {
}
