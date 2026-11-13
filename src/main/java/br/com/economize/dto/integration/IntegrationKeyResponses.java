package br.com.economize.dto.integration;

import java.time.OffsetDateTime;
import java.util.UUID;

/** As respostas das rotas de gestão das chaves de integração (V44). */
public final class IntegrationKeyResponses {

    private IntegrationKeyResponses() {
    }

    /**
     * Uma chave na lista — sem o segredo, que não existe mais em lugar nenhum
     * do servidor depois da criação.
     *
     * @param prefix     o começo do segredo, para reconhecer qual chave é qual
     * @param lastUsedAt aproximado: é atualizado no máximo a cada poucos minutos
     */
    public record IntegrationKeyItem(
            UUID id,
            String name,
            String prefix,
            OffsetDateTime createdAt,
            OffsetDateTime lastUsedAt,
            OffsetDateTime revokedAt,
            boolean revoked
    ) {
    }

    /**
     * A resposta da criação: a ÚNICA vez em que o segredo aparece.
     *
     * @param secret o valor a usar em {@code Authorization: Bearer ...}
     */
    public record IntegrationKeyCreated(
            IntegrationKeyItem key,
            String secret
    ) {
    }
}
