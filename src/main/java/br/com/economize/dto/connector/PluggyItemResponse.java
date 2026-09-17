package br.com.economize.dto.connector;

import br.com.economize.model.PluggyItem;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Conexão do usuário sem nenhum segredo: id interno, referência do item no
 * Pluggy, a identificação da instituição — e, desde 17/09/2026, a SAÚDE dela.
 *
 * <p>Antes, a listagem devolvia só {@code lastSyncedAt}, que é quando NÓS
 * lemos. O app exibia "sincronizado" enquanto mostrava um retrato do banco de
 * um mês atrás, com nove compras de cartão faltando e a fatura do mês zerada.
 * Os campos novos existem para a interface poder dizer o que está acontecendo
 * em vez de ficar calada: quando o BANCO foi lido de fato
 * ({@code providerUpdatedAt}), o que o provedor declara ({@code status}) e se
 * isso exige ação da pessoa ({@code needsAttention}).
 */
public record PluggyItemResponse(
        UUID id,
        String itemId,
        Long connectorId,
        String connectorName,
        OffsetDateTime createdAt,
        OffsetDateTime lastSyncedAt,
        String status,
        String executionStatus,
        String statusDetail,
        OffsetDateTime providerUpdatedAt,
        Long dataAgeHours,
        boolean needsAttention
) {

    public static PluggyItemResponse from(PluggyItem item) {
        return new PluggyItemResponse(
                item.getId(),
                item.getItemId(),
                item.getConnectorId(),
                item.getConnectorName(),
                item.getCreatedAt(),
                item.getLastSyncedAt(),
                item.getStatus(),
                item.getExecutionStatus(),
                item.getStatusDetail(),
                item.getProviderUpdatedAt(),
                // Long e não long: nunca perguntamos ao provedor é uma resposta
                // diferente de "coletado agora", e zero mentiria as duas.
                item.horasDesdeAColeta().isPresent() ? item.horasDesdeAColeta().getAsLong() : null,
                item.precisaDeAtencao());
    }
}
