package br.com.economize.dto.statement;

import jakarta.validation.constraints.Size;

/**
 * Apelido de transação (EC-094). Campo único de propósito: renomear é uma
 * decisão de apresentação e não pode virar porta de entrada para editar valor,
 * data ou categoria — esses têm caminhos próprios com regra de negócio.
 *
 * <p>Nulo ou em branco LIMPA o apelido: é assim que o usuário desfaz a
 * renomeação e a transação volta a se apresentar com o descritivo do banco.
 */
public record UpdateTransactionAliasRequest(
        @Size(max = 80, message = "Apelido deve ter no máximo 80 caracteres")
        String displayAlias
) {
    /** Mesmo limite de {@code bank_transactions.display_alias} na V13. */
    public static final int MAX_LENGTH = 80;
}
