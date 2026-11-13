package br.com.economize.dto.account;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * O que o dono informa ao separar dinheiro para uma fatura — EC-181.
 *
 * <p>{@code heldInAccountId} é opcional de propósito: há quem separe fora do que
 * o sistema enxerga. Quando vem, precisa ser conta do próprio usuário.
 */
public record UpsertInvoiceReserveRequest(
        @NotNull(message = "Informe o valor reservado")
        @DecimalMin(value = "0.01", message = "O valor reservado precisa ser maior que zero")
        // A coluna é NUMERIC(15,2): sem este limite, valor gigante ou com mais
        // de 2 casas passava pela validação e estourava no banco como 500
        @Digits(integer = 13, fraction = 2,
                message = "O valor reservado precisa ter no máximo 13 dígitos inteiros e 2 casas decimais")
        BigDecimal amount,

        UUID heldInAccountId,

        @Size(max = 200, message = "A anotação pode ter no máximo 200 caracteres")
        String note
) {
}
