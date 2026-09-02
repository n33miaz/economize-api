package br.com.economize.dto.family;

import br.com.economize.dto.statement.BankTransactionResponse;
import br.com.economize.model.BankTransaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma linha do extrato da casa (EC-149): os mesmos campos de
 * {@link BankTransactionResponse}, mais QUEM é o dono da linha.
 *
 * <p>Os campos são repetidos em vez de aninhados para que o app trate a lista
 * da casa com o mesmo componente da lista pessoal — só o selo do membro é
 * novo. A montagem delega ao {@code from} da resposta pessoal, então a
 * precedência do apelido e o que cada campo significa continuam decididos num
 * lugar só.
 */
public record FamilyTransactionResponse(
        UUID id,
        String transactionId,
        String type,
        BigDecimal amount,
        String description,
        String originalDescription,
        String displayAlias,
        OffsetDateTime date,
        UUID categoryId,
        BankTransaction.ReviewStatus reviewStatus,
        BankTransaction.CategorizedBy categorizedBy,
        BigDecimal confidence,
        String normalizedDescription,
        UUID uploadId,
        UUID accountId,
        boolean internalTransfer,
        /* o membro da casa dono da linha — o selo ao lado da origem */
        UUID memberId,
        String memberName
) {
    public static FamilyTransactionResponse from(BankTransaction tx, UUID memberId, String memberName) {
        BankTransactionResponse base = BankTransactionResponse.from(tx);
        return new FamilyTransactionResponse(
                base.id(),
                base.transactionId(),
                base.type(),
                base.amount(),
                base.description(),
                base.originalDescription(),
                base.displayAlias(),
                base.date(),
                base.categoryId(),
                base.reviewStatus(),
                base.categorizedBy(),
                base.confidence(),
                base.normalizedDescription(),
                base.uploadId(),
                base.accountId(),
                base.internalTransfer(),
                memberId,
                memberName);
    }
}
