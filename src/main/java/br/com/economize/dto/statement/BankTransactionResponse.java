package br.com.economize.dto.statement;

import br.com.economize.model.BankTransaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record BankTransactionResponse(
        UUID id,
        String transactionId,
        String type,
        BigDecimal amount,
        String description,
        OffsetDateTime date,
        UUID categoryId,
        BankTransaction.ReviewStatus reviewStatus,
        BankTransaction.CategorizedBy categorizedBy,
        BigDecimal confidence,
        String normalizedDescription,
        UUID uploadId
) {
    public static BankTransactionResponse from(BankTransaction tx) {
        return new BankTransactionResponse(
                tx.getId(),
                tx.getTransactionId(),
                tx.getType(),
                tx.getAmount(),
                tx.getDescription(),
                tx.getDate(),
                tx.getCategoryId(),
                tx.getReviewStatus(),
                tx.getCategorizedBy(),
                tx.getConfidence(),
                tx.getNormalizedDescription(),
                tx.getUploadId());
    }
}
