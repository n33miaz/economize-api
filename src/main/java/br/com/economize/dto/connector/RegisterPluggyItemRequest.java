package br.com.economize.dto.connector;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registro do item criado pelo widget Pluggy Connect: o app manda de volta o
 * itemId que o widget devolveu no onSuccess. A API confirma no Pluggy que o
 * item existe (e pertence a este usuário) antes de gravar o vínculo.
 */
public record RegisterPluggyItemRequest(
        @NotBlank(message = "itemId é obrigatório")
        @Size(max = 64, message = "itemId deve ter no máximo 64 caracteres")
        String itemId
) {
}
