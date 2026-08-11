package br.com.economize.service;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.StatementUpload;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.StatementUploadRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.event.DomainEventPublisher;
import br.com.economize.service.event.StatementImportedEvent;
import br.com.economize.service.statement.category.RuleBasedCategorizationService;
import br.com.economize.service.statement.parser.ParsedTransaction;
import br.com.economize.service.statement.parser.StatementFormat;
import br.com.economize.service.statement.parser.StatementParserFactory;
import br.com.economize.service.statement.parser.StatementParserStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankStatementService {

    private final BankTransactionRepository bankTransactionRepository;
    private final StatementUploadRepository statementUploadRepository;
    private final UserRepository userRepository;
    private final StatementParserFactory parserFactory;
    private final RuleBasedCategorizationService categorizationService;
    private final DomainEventPublisher eventPublisher;

    public Mono<ImportResult> processFile(String email, FilePart filePart) {
        return Mono.fromCallable(() -> userRepository.findByEmail(email)
                        .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado")))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(user -> DataBufferUtils.join(filePart.content())
                        .map(buffer -> {
                            byte[] bytes = new byte[buffer.readableByteCount()];
                            buffer.read(bytes);
                            DataBufferUtils.release(buffer);
                            return bytes;
                        })
                        .flatMap(bytes -> processBytes(user, filePart.filename(), bytes)));
    }

    private Mono<ImportResult> processBytes(User user, String fileName, byte[] bytes) {
        return Mono.fromCallable(() -> {
            StatementFormat format = StatementFormat.fromFilename(fileName);
            String hash = sha256(bytes);
            return statementUploadRepository.findByUserIdAndFileHash(user.getId(), hash)
                    .map(existing -> new ImportResult(existing.getTransactionsImported(), true, format.name()))
                    .orElseGet(() -> importFresh(user, fileName, bytes, format, hash));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private ImportResult importFresh(User user, String fileName, byte[] bytes, StatementFormat format, String hash) {
        StatementParserStrategy parser = parserFactory.resolve(format);
        List<ParsedTransaction> parsed = parser.parse(new ByteArrayInputStream(bytes));
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("Nenhuma transação encontrada no arquivo");
        }

        List<BankTransaction> toSave = new ArrayList<>();
        for (ParsedTransaction tx : parsed) {
            if (bankTransactionRepository.existsByUserIdAndTransactionId(user.getId(), tx.getExternalId())) {
                continue;
            }
            toSave.add(BankTransaction.builder()
                    .user(user)
                    .transactionId(tx.getExternalId())
                    .type(tx.getType())
                    .amount(tx.getAmount())
                    .description(tx.getDescription())
                    .date(tx.getDate())
                    .category(categorizationService.categorize(tx.getDescription(), tx.getType()).name())
                    .build());
        }

        if (!toSave.isEmpty()) {
            bankTransactionRepository.saveAll(toSave);
        }

        statementUploadRepository.save(StatementUpload.builder()
                .user(user)
                .fileHash(hash)
                .fileName(fileName)
                .format(format.name())
                .transactionsImported(toSave.size())
                .build());

        eventPublisher.publish(new StatementImportedEvent(user.getId(), format, toSave.size()));
        log.info("Importadas {} novas transações ({}), user={}", toSave.size(), format, user.getEmail());
        return new ImportResult(toSave.size(), false, format.name());
    }

    public List<BankTransaction> listTransactions(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        return bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId());
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao calcular hash", e);
        }
    }

    public record ImportResult(int transactionsImported, boolean duplicated, String format) {}
}
