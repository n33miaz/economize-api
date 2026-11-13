package br.com.economize.service.provider;

import br.com.economize.model.MarketSnapshot;
import br.com.economize.repository.MarketSnapshotRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * O uso do dia dos orçamentos de provedor ({@code QuoteBudget} da Brapi,
 * {@link AwesomeApiBudget}), gravado em {@code market_snapshots}.
 *
 * <p>
 * <b>Por que existe.</b> Os contadores viviam só em memória e zeravam a cada
 * reinício — e o Render grátis reinicia e adormece o container várias vezes por
 * dia. Cada boot devolvia o teto inteiro, e a soma dos "dias" de cada processo
 * estourava a cota real do provedor, que é uma só. Gravar o uso e lê-lo de
 * volta no boot faz o teto valer por dia de verdade, não por processo.
 *
 * <p>
 * <b>Chave</b>: {@code data:budget:<provedor>:<dia ISO>}. O prefixo
 * {@code data:} mantém a linha fora da hidratação e do agregado da Home (ver
 * {@link MarketSnapshotStore#DATA_PREFIX}); o dia na chave faz a virada não
 * precisar de apagar nada — o dia novo simplesmente não tem linha. Linhas de
 * dias passados saem com a limpeza de 7 dias do próprio store.
 *
 * <p>
 * <b>Frequência de gravação.</b> Não é a cada chamada: um UPSERT por requisição
 * ao provedor dobraria o trabalho de cada cotação. A gravação é adiada
 * ({@link #FLUSH_DELAY}) e coalescida — todas as chamadas dentro da janela
 * viram UMA escrita, com o valor exato do momento da escrita. O que se perde
 * num kill -9 é no máximo o uso dessa janela; o desligamento normal (o Render
 * manda SIGTERM ao adormecer e ao trocar de versão) grava na hora pelo
 * {@code @PreDestroy} de cada orçamento. Gravar "à frente" (reservar N de uma
 * vez) foi descartado: com reinícios frequentes, cada boot queimaria a sobra
 * da reserva anterior e comeria um teto de 120/dia da AwesomeAPI à toa.
 *
 * <p>
 * Banco fora do ar nunca derruba nada: leitura e escrita falham para WARN e o
 * orçamento segue em memória, como era antes.
 */
@Slf4j
@Component
public class BudgetUsageStore {

    public static final String KEY_PREFIX = MarketSnapshotStore.DATA_PREFIX + "budget:";

    /** Janela de coalescência das gravações; ver o javadoc da classe. */
    static final Duration FLUSH_DELAY = Duration.ofSeconds(15);

    private static final TypeReference<Map<String, Integer>> COUNTERS = new TypeReference<>() {
    };
    private static final String SOURCE = "budget";

    /** O uso de um dia: o dia a que os contadores se referem e os contadores. */
    public record Usage(LocalDate day, Map<String, Integer> counters) {
    }

    private final MarketSnapshotRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration flushDelay;
    private final Map<String, AtomicBoolean> pending = new ConcurrentHashMap<>();

    @Autowired
    public BudgetUsageStore(MarketSnapshotRepository repository, ObjectMapper mapper) {
        this(repository, mapper, Clock.systemUTC(), FLUSH_DELAY);
    }

    public BudgetUsageStore(MarketSnapshotRepository repository, ObjectMapper mapper, Clock clock,
            Duration flushDelay) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
        this.flushDelay = flushDelay;
    }

    public static String key(String provider, LocalDate day) {
        return KEY_PREFIX + provider + ":" + day;
    }

    /**
     * Contadores gravados para o dia; mapa vazio sem linha, sem banco ou com
     * erro. Bloqueante de propósito: só é chamado no boot, nunca no event loop.
     */
    public Map<String, Integer> load(String provider, LocalDate day) {
        if (repository == null) {
            return Map.of();
        }
        try {
            return repository.findById(key(provider, day))
                    .map(row -> read(row.getPayload()))
                    .orElse(Map.of());
        } catch (RuntimeException e) {
            log.warn("Uso diário de {} não pôde ser lido ({}); contando do zero", provider, e.toString());
            return Map.of();
        }
    }

    /**
     * Agenda a gravação do uso atual. Várias chamadas dentro da janela viram uma
     * só; o {@code snapshot} é lido no momento da escrita, então o valor gravado
     * é sempre o mais recente, nunca o do momento do agendamento.
     */
    public void scheduleFlush(String provider, Supplier<Usage> snapshot) {
        if (repository == null) {
            return;
        }
        AtomicBoolean flag = pending.computeIfAbsent(provider, ignored -> new AtomicBoolean());
        if (!flag.compareAndSet(false, true)) {
            return;
        }
        Mono.delay(flushDelay, Schedulers.boundedElastic())
                .subscribe(ignored -> {
                    // baixa a bandeira ANTES de ler: um acréscimo que chegar durante a
                    // escrita agenda outra, em vez de se perder
                    flag.set(false);
                    write(provider, snapshot.get());
                }, error -> {
                    flag.set(false);
                    log.warn("Gravação do uso diário de {} falhou: {}", provider, error.toString());
                });
    }

    /** Grava agora, de forma síncrona — é o caminho do desligamento. */
    public void write(String provider, Usage usage) {
        if (repository == null || usage == null) {
            return;
        }
        try {
            String json = mapper.writeValueAsString(usage.counters());
            repository.save(new MarketSnapshot(key(provider, usage.day()), json,
                    OffsetDateTime.now(clock.withZone(ZoneOffset.UTC)), SOURCE));
        } catch (Exception e) {
            log.warn("Uso diário de {} não persistido ({}); segue só em memória", provider, e.toString());
        }
    }

    private Map<String, Integer> read(String json) {
        try {
            Map<String, Integer> counters = mapper.readValue(json, COUNTERS);
            return counters == null ? Map.of() : counters;
        } catch (Exception e) {
            log.warn("Uso diário ilegível ({}); contando do zero", e.toString());
            return Map.of();
        }
    }
}
