package br.com.economize.dto.connector;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma conexão bancária do usuário, sem nenhum segredo e sem nome de provedor.
 *
 * <p>{@code connectorName} continua existindo porque o APK 2.2.0 o lê;
 * {@code institution} é o mesmo valor com o nome que o app novo usa — a
 * instituição conectada ("Nubank"), que é o que a pessoa reconhece na lista.
 * {@code itemId} é a referência opaca da conexão no provedor: o app só a
 * devolve, nunca a interpreta.
 * {@code status} e {@code needsAttention} são opcionais para clientes
 * anteriores. Os valores estáveis atuais de status são UPDATED, LOGIN_ERROR,
 * WAITING_USER_INPUT, OUTDATED, NOT_FOUND e NEEDS_USER_ACTION; nulo significa
 * que a saúde ainda não foi consultada.
 */
public record ConnectionResponse(
        UUID id,
        String itemId,
        Long connectorId,
        String connectorName,
        String institution,
        OffsetDateTime createdAt,
        OffsetDateTime lastSyncedAt,
        String status,
        boolean needsAttention
) {

    public static ConnectionResponse of(UUID id, String itemId, Long connectorId, String connectorName,
                                        OffsetDateTime createdAt, OffsetDateTime lastSyncedAt) {
        return new ConnectionResponse(id, itemId, connectorId, connectorName, connectorName, createdAt,
                lastSyncedAt, null, false);
    }

    public static ConnectionResponse of(UUID id, String itemId, Long connectorId, String connectorName,
                                        OffsetDateTime createdAt, OffsetDateTime lastSyncedAt, String status,
                                        boolean needsAttention) {
        return new ConnectionResponse(id, itemId, connectorId, connectorName, connectorName, createdAt,
                lastSyncedAt, status, needsAttention);
    }
}
