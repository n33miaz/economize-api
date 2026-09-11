package br.com.economize.dto.statement;

import br.com.economize.model.StatementUpload;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * O arquivo de onde um lote de lançamentos veio — EC-195.
 *
 * <p>Segue o mesmo padrão de {@code GET /accounts}: a listagem de transações
 * devolve só o {@code uploadId}, e é este recurso, pequeno o bastante para o
 * app carregar uma vez e casar em memória, que dá nome e data ao id. Repetir o
 * nome do arquivo em cada uma de 1.682 linhas seria pagar o mesmo texto mil
 * vezes para dizer a mesma coisa.
 *
 * @param fileName      o nome como o usuário o enviou; nulo em upload sem nome
 * @param format        CSV, OFX, PDF, XLSX, TXT
 * @param importedCount quantos lançamentos entraram por este arquivo
 * @param importedAt    quando ele foi processado
 */
public record ImportSourceResponse(
        UUID id,
        String fileName,
        String format,
        int importedCount,
        OffsetDateTime importedAt
) {
    public static ImportSourceResponse from(StatementUpload upload) {
        return new ImportSourceResponse(
                upload.getId(),
                upload.getFileName(),
                upload.getFormat(),
                upload.getTransactionsImported(),
                upload.getCreatedAt());
    }
}
