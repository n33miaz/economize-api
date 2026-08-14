package br.com.economize.service;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.CategoryRule;
import br.com.economize.model.StatementUpload;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRuleRepository;
import br.com.economize.repository.StatementUploadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * Escrita da importação num único commit (EC-075).
 *
 * <p>Existe como bean separado por dois motivos. O primeiro é o proxy: método
 * privado anotado com {@code @Transactional} não é interceptado, e o
 * {@code persist} do {@link BankStatementService} é privado. O segundo é o
 * WebFlux: os métodos públicos de lá devolvem {@code Mono} e a transação do
 * JPA vive numa ThreadLocal — aqui o trecho é bloqueante de ponta a ponta,
 * rodando inteiro na mesma thread do {@code boundedElastic}.
 *
 * <p>Antes, o upload e as transações iam em commits distintos e a falha do lote
 * era compensada apagando o upload na mão. Se a compensação também falhasse, o
 * arquivo ficava marcado como importado sem nenhuma transação — e o usuário não
 * conseguia mais reenviá-lo, porque o hash já constava.
 */
@Service
@RequiredArgsConstructor
public class StatementImportWriter {

    private final StatementUploadRepository statementUploadRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final CategoryRuleRepository categoryRuleRepository;

    /**
     * Grava o upload, carimba o id dele nas transações e salva tudo junto com os
     * hits das regras que pontuaram. Devolve o upload já com id.
     */
    @Transactional
    public StatementUpload write(StatementUpload upload,
                                 List<BankTransaction> transactions,
                                 Collection<CategoryRule> dirtyRules) {
        // o upload vai primeiro para carimbar o uploadId nas transações — é essa
        // amarração que permite "revisar esta importação" no app
        StatementUpload saved = statementUploadRepository.save(upload);
        for (BankTransaction tx : transactions) {
            tx.setUploadId(saved.getId());
        }
        if (!transactions.isEmpty()) {
            bankTransactionRepository.saveAll(transactions);
        }
        if (!dirtyRules.isEmpty()) {
            categoryRuleRepository.saveAll(dirtyRules);
        }
        return saved;
    }
}
