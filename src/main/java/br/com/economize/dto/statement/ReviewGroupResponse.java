package br.com.economize.dto.statement;

import br.com.economize.model.BankTransaction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A fila de revisão agrupa transações da mesma chave normalizada + mesma sugestão:
 * o usuário decide uma vez ("isso é Alimentação") e o app aplica ao grupo inteiro.
 */
public record ReviewGroupResponse(
        // chave do MOTOR: sai da descrição do banco e é ela que o agrupamento
        // usa — apelido não muda grupo nenhum (EC-094)
        String normalizedDescription,
        // amostra que o usuário lê para decidir: respeita o apelido, porque é
        // exatamente aqui que ele precisa reconhecer o gasto
        String sampleDescription,
        UUID suggestedCategoryId,
        BankTransaction.CategorizedBy categorizedBy,
        BigDecimal confidence,
        BigDecimal totalAmount,
        List<BankTransactionResponse> transactions
) {
    public static List<ReviewGroupResponse> groupsFrom(List<BankTransaction> pending) {
        Map<String, List<BankTransaction>> byKey = new LinkedHashMap<>();
        for (BankTransaction tx : pending) {
            String normalized = tx.getNormalizedDescription() != null ? tx.getNormalizedDescription() : "";
            String key = normalized + "|" + tx.getCategoryId();
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(tx);
        }

        List<ReviewGroupResponse> groups = new ArrayList<>();
        for (List<BankTransaction> txs : byKey.values()) {
            BankTransaction first = txs.get(0);
            BigDecimal total = txs.stream()
                    .map(BankTransaction::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            groups.add(new ReviewGroupResponse(
                    first.getNormalizedDescription(),
                    first.displayDescription(),
                    first.getCategoryId(),
                    first.getCategorizedBy(),
                    first.getConfidence(),
                    total,
                    txs.stream().map(BankTransactionResponse::from).toList()));
        }
        // grupos maiores primeiro: uma decisão resolve mais transações
        groups.sort(Comparator.comparingInt((ReviewGroupResponse g) -> g.transactions().size()).reversed());
        return groups;
    }
}
