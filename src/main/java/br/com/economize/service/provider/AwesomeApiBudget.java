package br.com.economize.service.provider;

import br.com.economize.config.MarketSourcesProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Teto diário de chamadas à AwesomeAPI, no mesmo espírito do
 * {@link br.com.economize.service.catalog.QuoteBudget} da Brapi.
 *
 * <p>
 * <b>O que a AwesomeAPI publica</b> (docs.awesomeapi.com.br, llms-full.txt,
 * lido em 06/09/2026): com cadastro e chave, "100 mil requisições mensais
 * gratuitas sem cache"; nos endpoints sequenciais ({@code /:moeda/:quantidade}
 * e {@code /daily}), "limite para requisições não autenticadas 100,
 * autenticadas 1500" — que é o teto de REGISTROS por chamada, não de chamadas.
 * O teto de chamadas anônimas por dia não é publicado em lugar nenhum; o que se
 * vê em produção é a resposta {@code 429 QuotaExceeded}, cedo no dia, e a
 * partir dela tudo bloqueado até a virada.
 *
 * <p>
 * Daí um orçamento conservador e explícito. A conta do consumo previsto:
 *
 * <pre>
 *   /all (Home)   : cache de 30 min → 48 recargas/dia           =  48
 *   /daily        : cache de 1 h por (moeda, janela), sob demanda ≤  72
 *   teto desta classe (economize.market.awesome-daily-budget)    = 120
 * </pre>
 *
 * <b>Reserva da Home.</b> O {@code /daily} é demanda ABERTA (qualquer
 * moeda, a qualquer hora), e o {@code /all} da Home tem pior caso FIXO. Sem
 * reserva, gráficos abertos em sequência gastavam o dia inteiro em minutos e a
 * Home de todo mundo perdia a cotação da AwesomeAPI até a virada. Por isso o
 * histórico ({@link Purpose#HISTORICAL}) tem teto próprio de
 * {@code teto - reserva} (120 - 48 = 72), e a Home ({@link Purpose#HOME}) pode
 * usar o teto inteiro — o mesmo desenho do {@code QuoteBudget} da Brapi.
 *
 * <p>
 * Estourado o teto, quem pediria à AwesomeAPI pula direto para as fontes
 * alternativas (Frankfurter, PTAX, CoinGecko, SGS) sem abrir conexão: um 429
 * a mais só aprofunda o bloqueio, e a resposta alternativa é igualmente boa
 * para a Home. A virada é por dia UTC porque é assim que o provedor conta.
 *
 * <p>
 * O uso do dia é persistido ({@link BudgetUsageStore}) e retomado no boot: os
 * reinícios do Render grátis devolviam o teto inteiro a cada processo. Contador
 * por instância, como o do QuoteBudget — o free tier roda uma só.
 */
@Slf4j
@Component
public class AwesomeApiBudget {

    /** De onde veio o pedido — é isso que decide se a reserva da Home protege. */
    public enum Purpose {
        /** {@code /all} da Home: pior caso fixo, pode usar o teto inteiro. */
        HOME,
        /** {@code /daily} do gráfico: demanda aberta, teto de {@code teto - reserva}. */
        HISTORICAL
    }

    /** Nome do provedor na chave persistida ({@code data:budget:awesome:<dia>}). */
    static final String PROVIDER = "awesome";

    private final int dailyLimit;
    private final int historicalLimit;
    private final Clock clock;
    private final BudgetUsageStore usageStore;

    private LocalDate currentDay;
    private int used;
    private int historicalUsed;

    /** Só memória — é o modo dos testes de unidade. */
    public AwesomeApiBudget(MarketSourcesProperties properties) {
        this(properties.getAwesomeDailyBudget(), properties.getAwesomeHomeReserve(), Clock.systemUTC(), null);
    }

    @Autowired
    public AwesomeApiBudget(MarketSourcesProperties properties, BudgetUsageStore usageStore) {
        this(properties.getAwesomeDailyBudget(), properties.getAwesomeHomeReserve(), Clock.systemUTC(),
                usageStore);
    }

    AwesomeApiBudget(int dailyLimit, Clock clock) {
        this(dailyLimit, 0, clock, null);
    }

    AwesomeApiBudget(int dailyLimit, int homeReserve, Clock clock, BudgetUsageStore usageStore) {
        this.dailyLimit = dailyLimit;
        this.historicalLimit = Math.max(0, dailyLimit - Math.max(0, homeReserve));
        this.clock = clock;
        this.usageStore = usageStore;
        this.currentDay = today();
    }

    /**
     * Retoma o uso do dia gravado por um processo anterior. {@code max} e não
     * soma: o que este processo já contou antes do boot terminar é o mesmo
     * dinheiro, não outro.
     */
    @PostConstruct
    public synchronized void restore() {
        if (usageStore == null) {
            return;
        }
        rolloverIfNeeded();
        Map<String, Integer> saved = usageStore.load(PROVIDER, currentDay);
        used = Math.max(used, saved.getOrDefault("used", 0));
        historicalUsed = Math.max(historicalUsed, saved.getOrDefault("historicalUsed", 0));
        if (used > 0) {
            log.info("Orçamento da AwesomeAPI retomado: {} de {} já usados hoje ({} do histórico)",
                    used, dailyLimit, historicalUsed);
        }
    }

    /** Grava o uso na hora: o Render manda SIGTERM ao adormecer e ao publicar versão. */
    @PreDestroy
    public void flush() {
        if (usageStore != null) {
            usageStore.write(PROVIDER, usage());
        }
    }

    /** Reserva UMA chamada para a Home; false quando o dia já gastou o teto. */
    public boolean tryAcquire() {
        return tryAcquire(Purpose.HOME);
    }

    /** Reserva UMA chamada para {@code purpose}; false quando a fatia dele acabou. */
    public synchronized boolean tryAcquire(Purpose purpose) {
        rolloverIfNeeded();
        if (available(purpose) <= 0) {
            return false;
        }
        used++;
        if (purpose == Purpose.HISTORICAL) {
            historicalUsed++;
        }
        if (used == dailyLimit) {
            log.warn("Orçamento diário da AwesomeAPI esgotado ({}/dia); fontes alternativas até a virada UTC",
                    dailyLimit);
        } else if (purpose == Purpose.HISTORICAL && historicalUsed == historicalLimit) {
            log.warn("Fatia do histórico na AwesomeAPI esgotada ({}/dia); o resto fica reservado à Home",
                    historicalLimit);
        }
        if (usageStore != null) {
            usageStore.scheduleFlush(PROVIDER, this::usage);
        }
        return true;
    }

    /** Quanto ainda cabe para a Home (o teto inteiro menos o que já saiu). */
    public synchronized int remaining() {
        return remaining(Purpose.HOME);
    }

    public synchronized int remaining(Purpose purpose) {
        rolloverIfNeeded();
        return available(purpose);
    }

    private int available(Purpose purpose) {
        int total = Math.max(0, dailyLimit - used);
        if (purpose == Purpose.HISTORICAL) {
            return Math.min(total, Math.max(0, historicalLimit - historicalUsed));
        }
        return total;
    }

    private synchronized BudgetUsageStore.Usage usage() {
        return new BudgetUsageStore.Usage(currentDay, Map.of("used", used, "historicalUsed", historicalUsed));
    }

    public int limit() {
        return dailyLimit;
    }

    private void rolloverIfNeeded() {
        LocalDate today = today();
        if (!today.equals(currentDay)) {
            currentDay = today;
            used = 0;
            historicalUsed = 0;
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
