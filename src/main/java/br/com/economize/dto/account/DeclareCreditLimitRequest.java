package br.com.economize.dto.account;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * O limite do cartão, dito por quem sabe.
 *
 * <p><b>Por que perguntar.</b> O limite não existe em nada do que o app
 * importa. A fatura em OFX declara o valor DEVIDO no bloco de saldo, o CSV é
 * uma lista de compras, e o agregador devolve limite em algumas instituições e
 * em outras não. Perguntar não é preguiça de integração: é a única fonte que
 * funciona para todo mundo.
 *
 * <p><b>Os dois campos são excludentes na prática.</b> Ou este cartão tem
 * limite próprio ({@code creditLimit}), ou ele divide a bolsa de outro
 * ({@code sharedWithAccountId}) — o caso do cartão virtual e do adicional, que
 * o próprio dono levantou: <i>"normalmente um crédito vale para vários
 * cartões"</i>. Mandar os dois nulos APAGA o que estava informado, e isso é
 * deliberado: é como o usuário desfaz uma informação errada.
 */
public record DeclareCreditLimitRequest(
        @Schema(description = "Limite total do cartão. Nulo apaga o que estava informado.",
                example = "5000.00")
        @PositiveOrZero(message = "O limite não pode ser negativo")
        // 15 inteiros é o teto da coluna (NUMERIC(19,4)); sem isto um valor
        // absurdo só falharia lá embaixo, no banco, como erro 500
        @Digits(integer = 15, fraction = 4, message = "Valor de limite fora da faixa aceita")
        BigDecimal creditLimit,

        @Schema(description = "Conta dona do limite, quando ele é compartilhado com outro cartão")
        UUID sharedWithAccountId) {
}
