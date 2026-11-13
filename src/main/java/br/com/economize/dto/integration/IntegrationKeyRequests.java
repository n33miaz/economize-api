package br.com.economize.dto.integration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Os corpos das rotas de gestão das chaves de integração (V44). */
public final class IntegrationKeyRequests {

    private IntegrationKeyRequests() {
    }

    /** @param name como a pessoa vai reconhecer a chave na lista ("UniOver") */
    public record CreateIntegrationKey(
            @NotBlank(message = "Dê um nome à chave")
            @Size(max = 60, message = "O nome da chave tem no máximo 60 caracteres")
            String name
    ) {
    }
}
