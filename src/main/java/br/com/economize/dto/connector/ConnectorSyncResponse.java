package br.com.economize.dto.connector;

import br.com.economize.service.connector.OpenFinanceProvider;

import java.util.List;
import java.util.UUID;

/**
 * Resultado de uma sincronização — os mesmos campos que o upload de extrato
 * devolve, mais quantas conexões foram percorridas. É o mesmo shape do
 * {@code POST /connectors/pluggy/sync} legado, agora tipado.
 */
public record ConnectorSyncResponse(
        UUID uploadId,
        int transactionsImported,
        int suggested,
        int uncategorized,
        int reconciled,
        String format,
        int itemsSynced,
        // Todos os uploads desta sincronização que receberam lançamento novo.
        // uploadId continua sendo o principal para clientes publicados.
        List<UUID> uploadIds,
        // Campo novo e aditivo: o app publicado ignora; o novo mostra quais
        // bancos ficaram de fora por pedirem reconexão. Vazio quando nenhum
        List<OpenFinanceProvider.SkippedConnection> skippedConnections
) {

    public static ConnectorSyncResponse from(OpenFinanceProvider.SyncResult sync) {
        var result = sync.result();
        return new ConnectorSyncResponse(result.uploadId(), result.transactionsImported(), result.suggested(),
                result.uncategorized(), result.reconciled(), result.format(), sync.itemsSynced(),
                result.uploadIds(), sync.skippedConnections());
    }
}
