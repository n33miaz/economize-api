package br.com.economize.service;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.StatementUpload;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.StatementUploadRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.event.DomainEventPublisher;
import br.com.economize.service.event.StatementImportedEvent;
import br.com.economize.service.statement.category.AiCategorySuggester;
import br.com.economize.service.statement.category.CategorizationEngine;
import br.com.economize.service.statement.category.DescriptionNormalizer;
import br.com.economize.service.statement.parser.ParsedTransaction;
import br.com.economize.service.statement.parser.StatementFormat;
import br.com.economize.service.statement.parser.StatementParserFactory;
import br.com.economize.service.statement.parser.StatementParserStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankStatementService {

    // sugestão de IA nunca supera regra ou keyword — entra com a menor confiança
    private static final BigDecimal CONF_AI = new BigDecimal("0.50");

    private static final int DESCRIPTION_MAX = 255;

    private final BankTransactionRepository bankTransactionRepository;
    private final StatementUploadRepository statementUploadRepository;
    private final UserRepository userRepository;
    private final StatementParserFactory parserFactory;
    private final CategorizationEngine categorizationEngine;
    private final CategoryRepository categoryRepository;
    private final StatementImportWriter importWriter;
    private final DomainEventPublisher eventPublisher;
    // presente só com a feature flag AI_CATEGORIZATION_ENABLED ligada
    private final ObjectProvider<AiCategorySuggester> aiSuggester;

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
                    .map(existing -> duplicatedResult(user, existing, format))
                    .orElseGet(() -> importFresh(user, fileName, bytes, format, hash));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // reimport do mesmo arquivo devolve o estado real da importação original,
    // inclusive quantas transações dela ainda aguardam revisão
    private ImportResult duplicatedResult(User user, StatementUpload existing, StatementFormat format) {
        List<BankTransaction> txs = bankTransactionRepository
                .findAllByUserIdAndUploadIdOrderByDateDesc(user.getId(), existing.getId());
        int suggested = 0;
        int uncategorized = 0;
        for (BankTransaction tx : txs) {
            if (tx.getReviewStatus() == BankTransaction.ReviewStatus.SUGGESTED) suggested++;
            if (tx.getReviewStatus() == BankTransaction.ReviewStatus.UNCATEGORIZED) uncategorized++;
        }
        return new ImportResult(existing.getId(), existing.getTransactionsImported(),
                suggested, uncategorized, 0, true, format.name());
    }

    private ImportResult importFresh(User user, String fileName, byte[] bytes, StatementFormat format, String hash) {
        StatementParserStrategy parser = parserFactory.resolve(format);
        List<ParsedTransaction> parsed = parser.parse(new ByteArrayInputStream(bytes));
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("Nenhuma transação encontrada no arquivo");
        }
        return persist(user, parsed, fileName, format, hash);
    }

    /**
     * Entrada dos conectores (Meu Pluggy): mesmas garantias do upload — motor de
     * categorização, dedup por id externo e reconciliação entre fontes. O hash
     * sintético único registra cada sincronização no histórico de uploads.
     */
    public ImportResult importFromConnector(User user, String sourceName, StatementFormat format,
                                            List<ParsedTransaction> parsed) {
        if (parsed.isEmpty()) {
            return new ImportResult(null, 0, 0, 0, 0, false, format.name());
        }
        String syntheticHash = sha256((sourceName + "|" + UUID.randomUUID())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return persist(user, parsed, sourceName, format, syntheticHash);
    }

    private ImportResult persist(User user, List<ParsedTransaction> parsed, String fileName,
                                 StatementFormat format, String hash) {
        List<BankTransaction> existing = existingInWindow(user, parsed);
        // os ids já conhecidos saem da mesma leitura da janela: perguntar ao banco
        // uma vez por transação custava ~1 minuto num backfill de dois anos
        Set<String> knownIds = new HashSet<>();
        for (BankTransaction tx : existing) {
            if (tx.getTransactionId() != null) knownIds.add(tx.getTransactionId());
        }
        ReconciliationLedger ledger = new ReconciliationLedger(existing);

        // 1º passe: o que já existe pelo id externo sai da frente, mas consome o
        // crédito da PRÓPRIA linha no ledger — senão uma transação nova de mesmo
        // dia e valor herdaria esse crédito e seria descartada no lugar dela
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seenInFile = new HashSet<>();
        for (ParsedTransaction tx : parsed) {
            String normalized = DescriptionNormalizer.normalize(tx.getDescription());
            // id repetido dentro do próprio arquivo violaria unique_transaction_per_user
            if (tx.getExternalId() != null && !seenInFile.add(tx.getExternalId())) continue;
            if (knownIds.contains(tx.getExternalId())) {
                ledger.consume(tx.getDate(), tx.getAmount(), normalized);
                continue;
            }
            candidates.add(new Candidate(tx, normalized));
        }

        // 2º passe: casamento exato (dia+valor+descrição normalizada) antes de
        // qualquer aproximação. Fazer os dois níveis no mesmo passe deixava a
        // primeira linha do arquivo consumir o crédito de uma transação diferente
        // e descartar um lançamento legítimo, enquanto a duplicata real entrava.
        List<Candidate> unmatched = new ArrayList<>();
        int reconciled = 0;
        for (Candidate c : candidates) {
            if (ledger.consumeExact(c.tx().getDate(), c.tx().getAmount(), c.normalized())) {
                reconciled++;
            } else {
                unmatched.add(c);
            }
        }

        // 3º passe: o mesmo lançamento descrito de outro jeito por outro formato
        List<Candidate> fresh = new ArrayList<>();
        for (Candidate c : unmatched) {
            if (ledger.consumeAny(c.tx().getDate(), c.tx().getAmount())) {
                reconciled++;
            } else {
                fresh.add(c);
            }
        }

        // categorizar só o que de fato entra: rodar antes da reconciliação inflava
        // o contador de acertos das regras com transações descartadas
        CategorizationEngine.Context ctx = categorizationEngine.contextFor(user.getId());
        List<BankTransaction> toSave = new ArrayList<>();
        for (Candidate candidate : fresh) {
            ParsedTransaction tx = candidate.tx();
            CategorizationEngine.Result result =
                    categorizationEngine.categorize(ctx, tx.getDescription(), tx.getType());
            toSave.add(BankTransaction.builder()
                    .user(user)
                    .transactionId(tx.getExternalId())
                    .type(tx.getType())
                    .amount(tx.getAmount())
                    .description(truncate(tx.getDescription()))
                    .date(tx.getDate())
                    .categoryId(result.resolved() ? result.category().getId() : null)
                    .category(result.resolved() ? TransactionReviewService.legacyKey(result.category()) : null)
                    .reviewStatus(result.resolved()
                            ? BankTransaction.ReviewStatus.SUGGESTED
                            : BankTransaction.ReviewStatus.UNCATEGORIZED)
                    .categorizedBy(result.by())
                    .confidence(result.confidence())
                    .normalizedDescription(result.normalizedDescription())
                    .build());
        }

        applyAiSuggestions(user, toSave);

        int suggested = 0;
        int uncategorized = 0;
        for (BankTransaction tx : toSave) {
            if (tx.getReviewStatus() == BankTransaction.ReviewStatus.SUGGESTED) suggested++;
            if (tx.getReviewStatus() == BankTransaction.ReviewStatus.UNCATEGORIZED) uncategorized++;
        }

        // upload + transações + hits das regras num único commit (EC-075):
        // qualquer falha desfaz o conjunto e o arquivo continua reenviável
        StatementUpload upload = importWriter.write(
                StatementUpload.builder()
                        .user(user)
                        .fileHash(hash)
                        .fileName(fileName)
                        .format(format.name())
                        .transactionsImported(toSave.size())
                        .build(),
                toSave,
                ctx.getDirtyRules());

        eventPublisher.publish(new StatementImportedEvent(user.getId(), format, toSave.size(), upload.getId()));
        log.info("Importadas {} novas transações ({}): {} sugeridas, {} sem categoria, {} reconciliadas, user={}",
                toSave.size(), format, suggested, uncategorized, reconciled, user.getEmail());
        return new ImportResult(upload.getId(), toSave.size(), suggested, uncategorized, reconciled, false, format.name());
    }

    /**
     * Tudo que o usuário já tem dentro da janela de datas do arquivo — base do
     * ledger de reconciliação e também dos ids externos já conhecidos.
     */
    private List<BankTransaction> existingInWindow(User user, List<ParsedTransaction> parsed) {
        OffsetDateTime min = null;
        OffsetDateTime max = null;
        for (ParsedTransaction tx : parsed) {
            if (min == null || tx.getDate().isBefore(min)) min = tx.getDate();
            if (max == null || tx.getDate().isAfter(max)) max = tx.getDate();
        }
        // a janela é recortada no MESMO fuso da chave do ledger (UTC); derivar o
        // dia no offset da data parseada deslocava a janela quando a data não
        // vinha em UTC e nada reconciliava
        return bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        user.getId(),
                        utcDay(min).atStartOfDay().atOffset(java.time.ZoneOffset.UTC),
                        utcDay(max).plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
    }

    /** Transação do arquivo com a descrição já normalizada uma única vez. */
    private record Candidate(ParsedTransaction tx, String normalized) {
    }

    /**
     * Ledger de reconciliação entre fontes: conta quantas transações já existem
     * para cada trinca (dia, valor, descrição normalizada) dentro da janela do
     * arquivo — e um segundo nível só (dia, valor) para o mesmo lançamento
     * descrito de forma diferente por formatos distintos (o OFX do Inter tem
     * "Cp :NNN-Fulano" onde o CSV tem "Fulano"). O consumo é por contagem:
     * duas compras idênticas no mesmo dia continuam sendo duas — só o excedente
     * do arquivo é importado. Os dois níveis são consumidos em passes separados
     * (ver {@code persist}): misturados, uma descrição diferente consumia o
     * crédito reservado ao casamento exato de outra transação.
     */
    static class ReconciliationLedger {
        private final Map<String, Integer> exact = new java.util.HashMap<>();
        private final Map<String, Integer> byDayAmount = new java.util.HashMap<>();

        ReconciliationLedger(List<BankTransaction> existing) {
            for (BankTransaction tx : existing) {
                String base = dayAmountKey(tx.getDate(), tx.getAmount());
                byDayAmount.merge(base, 1, Integer::sum);
                if (tx.getNormalizedDescription() != null && !tx.getNormalizedDescription().isBlank()) {
                    exact.merge(base + "|" + tx.getNormalizedDescription(), 1, Integer::sum);
                }
            }
        }

        /** Casamento forte: mesmo dia, mesmo valor e mesma descrição normalizada. */
        boolean consumeExact(OffsetDateTime date, java.math.BigDecimal amount, String normalizedDescription) {
            String base = dayAmountKey(date, amount);
            String exactKey = base + "|" + normalizedDescription;
            Integer exactCount = exact.get(exactKey);
            if (exactCount == null || exactCount <= 0) return false;
            exact.put(exactKey, exactCount - 1);
            decrementBase(base);
            return true;
        }

        /** Rede: o mesmo lançamento que dois formatos descrevem de jeitos diferentes. */
        boolean consumeAny(OffsetDateTime date, java.math.BigDecimal amount) {
            String base = dayAmountKey(date, amount);
            Integer baseCount = byDayAmount.get(base);
            if (baseCount == null || baseCount <= 0) return false;
            byDayAmount.put(base, baseCount - 1);
            return true;
        }

        void consume(OffsetDateTime date, java.math.BigDecimal amount, String normalizedDescription) {
            if (!consumeExact(date, amount, normalizedDescription)) consumeAny(date, amount);
        }

        private void decrementBase(String base) {
            Integer baseCount = byDayAmount.get(base);
            if (baseCount != null && baseCount > 0) byDayAmount.put(base, baseCount - 1);
        }

        private static String dayAmountKey(OffsetDateTime date, java.math.BigDecimal amount) {
            return utcDay(date) + "|" + amount.stripTrailingZeros().toPlainString();
        }
    }

    private static java.time.LocalDate utcDay(OffsetDateTime date) {
        return date.atZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDate();
    }

    /**
     * `bank_transactions.description` é VARCHAR(255) e os parsers passaram a
     * concatenar campos (MEMO+NAME no OFX, Histórico+Descrição no CSV): sem o
     * corte, uma linha comprida derruba o lote inteiro na gravação.
     */
    private static String truncate(String description) {
        if (description == null || description.length() <= DESCRIPTION_MAX) return description;
        return description.substring(0, DESCRIPTION_MAX);
    }

    private void applyAiSuggestions(User user, List<BankTransaction> toSave) {
        AiCategorySuggester suggester = aiSuggester.getIfAvailable();
        if (suggester == null) return;
        List<BankTransaction> unresolved = toSave.stream()
                .filter(tx -> tx.getCategoryId() == null)
                .filter(tx -> tx.getNormalizedDescription() != null && !tx.getNormalizedDescription().isBlank())
                .toList();
        if (unresolved.isEmpty()) return;

        List<Category> catalog = categoryRepository.findVisibleTo(user.getId()).stream()
                .filter(c -> !c.isArchived())
                .toList();
        List<String> keys = unresolved.stream()
                .map(BankTransaction::getNormalizedDescription)
                .distinct()
                .toList();
        Map<String, String> suggestions = suggester.suggest(keys, catalog);
        if (suggestions.isEmpty()) return;

        Map<String, Category> bySlug = catalog.stream()
                .collect(Collectors.toMap(Category::getSlug, Function.identity(), (a, b) -> a));
        for (BankTransaction tx : unresolved) {
            Category category = bySlug.get(suggestions.get(tx.getNormalizedDescription()));
            if (category == null) continue;
            tx.setCategoryId(category.getId());
            tx.setCategory(TransactionReviewService.legacyKey(category));
            tx.setReviewStatus(BankTransaction.ReviewStatus.SUGGESTED);
            tx.setCategorizedBy(BankTransaction.CategorizedBy.AI);
            tx.setConfidence(CONF_AI);
        }
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

    public record ImportResult(UUID uploadId, int transactionsImported, int suggested,
                               int uncategorized, int reconciled, boolean duplicated, String format) {
    }
}
