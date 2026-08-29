package br.com.economize.dto.connector;

import br.com.economize.model.PluggyItem;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Conexão do usuário sem nenhum segredo: id interno, referência do item no
 * Pluggy e a identificação da instituição para a listagem no app.
 */
public record PluggyItemResponse(
        UUID id,
        String itemId,
        Long connectorId,
        String connectorName,
        OffsetDateTime createdAt,
        OffsetDateTime lastSyncedAt
) {

    public static PluggyItemResponse from(PluggyItem item) {
        return new PluggyItemResponse(item.getId(), item.getItemId(), item.getConnectorId(),
                item.getConnectorName(), item.getCreatedAt(), item.getLastSyncedAt());
    }
}
