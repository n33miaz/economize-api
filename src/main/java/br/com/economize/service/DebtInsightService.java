package br.com.economize.service;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.analytics.DebtOverviewResponse;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.statement.category.DebtClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Quanto do ciclo é dívida, e de que tipo (EC-139).
 *
 * <p>Até aqui o app somava a parcela do financiamento com o mercado e chamava
 * tudo de despesa. São coisas diferentes: uma é consumo do mês, a outra é
 * compromisso assumido em outro mês que ainda está cobrando. Sem separar, a
 * pergunta "por que não sobra nada?" não tem resposta.
 *
 * <p>A classificação é <b>derivada da descrição</b>, não gravada: o extrato é a
 * fonte, e uma coluna nova exigiria decidir o que fazer com o histórico já
 * importado. Derivar mantém tudo consistente e deixa a regra evoluir sem
 * migration.
 *
 * <p>Perna de transferência interna fica de fora, pela mesma razão de sempre:
 * pagar a fatura não é despesa — a despesa foi a compra.
 */
@Service
@RequiredArgsConstructor
public class DebtInsightService {

    /** Teto de exemplos por tipo: a tela mostra, não audita. */
    private static final int MAX_ITEMS_PER_KIND = 12;

    private final BankTransactionRepository bankTransactionRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public DebtOverviewResponse summarize(String email, AnalysisWindow window) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));

        List<BankTransaction> transactions =
                bankTransactionRepository.findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        user.getId(), window.startInstant(), window.endExclusiveInstant());

        Map<DebtClassifier.DebtKind, Bucket> buckets = new EnumMap<>(DebtClassifier.DebtKind.class);
        BigDecimal totalExpense = BigDecimal.ZERO;
        BigDecimal totalDebt = BigDecimal.ZERO;

        for (BankTransaction tx : transactions) {
            if (tx.isInternalTransfer()) continue;
            if (!isExpense(tx)) continue;

            BigDecimal amount = tx.getAmount() != null ? tx.getAmount().abs() : BigDecimal.ZERO;
            totalExpense = totalExpense.add(amount);

            // O apelido do usuário vence a descrição do banco quando existe: é
            // ele que a pessoa reconhece, e a classificação segue o mesmo texto
            // que a tela mostra
            String label = tx.getDisplayAlias() != null && !tx.getDisplayAlias().isBlank()
                    ? tx.getDisplayAlias() : tx.getDescription();
            DebtClassifier.DebtSignal signal = DebtClassifier.classify(label);
            if (!signal.isDebt()) continue;

            totalDebt = totalDebt.add(amount);
            Bucket bucket = buckets.computeIfAbsent(signal.kind(), k -> new Bucket());
            bucket.total = bucket.total.add(amount);
            bucket.count++;
            if (bucket.items.size() < MAX_ITEMS_PER_KIND) {
                bucket.items.add(new DebtOverviewResponse.DebtEntry(
                        tx.getId(), label, amount,
                        tx.getDate() != null ? tx.getDate().toLocalDate() : null,
                        signal.installment(), signal.total(), signal.remaining()));
            }
        }

        List<DebtOverviewResponse.DebtGroup> groups = new ArrayList<>();
        for (Map.Entry<DebtClassifier.DebtKind, Bucket> entry : buckets.entrySet()) {
            Bucket bucket = entry.getValue();
            groups.add(new DebtOverviewResponse.DebtGroup(
                    entry.getKey().name(), scale2(bucket.total), bucket.count, bucket.items));
        }
        // do mais pesado para o mais leve: é a ordem em que a pergunta se responde
        groups.sort(Comparator.comparing(DebtOverviewResponse.DebtGroup::total).reversed());

        return new DebtOverviewResponse(
                window.monthLabel(), window.start(), window.end(),
                scale2(totalExpense), scale2(totalDebt),
                sharePct(totalDebt, totalExpense),
                groups,
                buckets.containsKey(DebtClassifier.DebtKind.REVOLVING));
    }

    /**
     * OFX pode trazer TRNTYPE fora do padrão; nesse caso o sinal decide — a
     * mesma regra da consolidação mensal.
     */
    private static boolean isExpense(BankTransaction tx) {
        if ("DEBIT".equalsIgnoreCase(tx.getType())) return true;
        if ("CREDIT".equalsIgnoreCase(tx.getType())) return false;
        return tx.getAmount() != null && tx.getAmount().signum() < 0;
    }

    /** {@code null} em vez de zero quando não houve despesa: 0/0 não é 0%. */
    private static BigDecimal sharePct(BigDecimal debt, BigDecimal expense) {
        if (expense == null || expense.signum() == 0) return null;
        return debt.multiply(BigDecimal.valueOf(100))
                .divide(expense, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static final class Bucket {
        BigDecimal total = BigDecimal.ZERO;
        int count;
        final List<DebtOverviewResponse.DebtEntry> items = new ArrayList<>();
    }
}
