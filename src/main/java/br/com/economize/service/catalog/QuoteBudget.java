package br.com.economize.service.catalog;

import br.com.economize.config.MarketCatalogProperties;
import br.com.economize.service.provider.BudgetUsageStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

/**
 * Teto diário de requisições ao provedor de ações (Brapi), para TODAS as
 * chamadas — não só as do catálogo.
 *
 * <p>
 * A Brapi cobra <b>uma requisição por ticker</b> contra uma cota de ~1.000/dia.
 * Antes, só a rolagem do catálogo passava por aqui: o {@code /all} da Home e a
 * busca do usuário gastavam a mesma cota sem nenhum controle, e a soma estourava
 * o plano — a partir daí TODO mundo levava 429 até a virada do dia. Hoje o
 * estrangulamento é único ({@code BrapiProvider}) e a conta fecha assim:
 *
 * <pre>
 *   /all (Home)   : cache de 30 min → 48 recargas/dia × 7 tickers = 336/dia
 *   catálogo+busca: teto discricionário                          = 500/dia
 *                                                          soma  = 836/dia
 *   teto total desta classe                                      = 850/dia
 *   cota do plano                                              ≈ 1.000/dia
 *                                                          folga ≈ 164 (16%)
 * </pre>
 *
 * <p>
 * Os dois níveis existem porque o pior caso do {@code /all} é FIXO (336) e o do
 * catálogo é aberto (o usuário rola o quanto quiser). Deixando a rolagem presa a
 * 500 dentro de um total de 850, sobram sempre ≥ 350 para a Home — ou seja,
 * rolagem profunda nunca derruba o "Mercado agora" de ninguém. O contrário é
 * permitido de propósito: se a Home não gastou tudo, a busca não herda a sobra
 * (o teto discricionário não sobe), mas se o dia foi fraco de rolagem a Home
 * segue com folga de sobra.
 *
 * <p>
 * Estourado o orçamento, quem pede cotação recebe o último snapshot bom
 * ({@code MarketSnapshotStore}) em vez de erro: preço mais velho é aceitável,
 * Home vazia não.
 *
 * <p>
 * O uso do dia é gravado em {@code market_snapshots} ({@link BudgetUsageStore})
 * e lido de volta no boot: sem isso cada reinício do Render devolvia o teto
 * inteiro, e a cota real da Brapi (uma só por dia) estourava.
 *
 * <p>
 * <b>Limitação conhecida e aceita:</b> a contagem é por instância. Com mais de
 * uma instância gravando a mesma linha, o teto real seria o número de
 * instâncias × este teto — hoje o free tier roda uma só, e um contador
 * compartilhado de verdade exigiria dependência nova (Redis).
 */
@Slf4j
@Component
public class QuoteBudget {

    /** De onde veio o pedido — é isso que decide se a reserva da Home protege. */
    public enum Purpose {
        /** Conjunto padrão do {@code /all}: pior caso fixo, tem reserva. */
        HOME,
        /** Catálogo paginado e busca do usuário: demanda aberta, tem teto próprio. */
        ON_DEMAND
    }

    /** Nome do provedor na chave persistida ({@code data:budget:brapi:<dia>}). */
    static final String PROVIDER = "brapi";

    private final int dailyLimit;
    private final int onDemandLimit;
    private final Clock clock;
    private final BudgetUsageStore usageStore;

    private LocalDate currentDay;
    private int used;
    private int onDemandUsed;

    /** Só memória — é o modo dos testes de unidade. */
    public QuoteBudget(MarketCatalogProperties properties) {
        this(properties, Clock.systemDefaultZone(), null);
    }

    @Autowired
    public QuoteBudget(MarketCatalogProperties properties, BudgetUsageStore usageStore) {
        this(properties, Clock.systemDefaultZone(), usageStore);
    }

    QuoteBudget(MarketCatalogProperties properties, Clock clock) {
        this(properties, clock, null);
    }

    QuoteBudget(MarketCatalogProperties properties, Clock clock, BudgetUsageStore usageStore) {
        this.dailyLimit = properties.getDailyProviderBudget();
        this.onDemandLimit = properties.getDailyQuoteBudget();
        this.clock = clock;
        this.usageStore = usageStore;
        this.currentDay = LocalDate.now(clock);
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
        onDemandUsed = Math.max(onDemandUsed, saved.getOrDefault("onDemandUsed", 0));
        if (used > 0) {
            log.info("Orçamento da Brapi retomado: {} de {} já usados hoje ({} sob demanda)",
                    used, dailyLimit, onDemandUsed);
        }
    }

    /** Grava o uso na hora: o Render manda SIGTERM ao adormecer e ao publicar versão. */
    @PreDestroy
    public void flush() {
        if (usageStore != null) {
            usageStore.write(PROVIDER, usage());
        }
    }

    private synchronized BudgetUsageStore.Usage usage() {
        return new BudgetUsageStore.Usage(currentDay, Map.of("used", used, "onDemandUsed", onDemandUsed));
    }

    /**
     * Reserva até {@code wanted} requisições e devolve quantas foram liberadas.
     * Concede parcial de propósito: metade da página cotada é melhor do que
     * página inteira sem preço.
     */
    public synchronized int tryAcquire(int wanted, Purpose purpose) {
        if (wanted <= 0) {
            return 0;
        }
        rolloverIfNeeded();
        int granted = Math.min(wanted, available(purpose));
        used += granted;
        if (purpose == Purpose.ON_DEMAND) {
            onDemandUsed += granted;
        }
        if (granted > 0 && usageStore != null) {
            usageStore.scheduleFlush(PROVIDER, this::usage);
        }
        if (granted < wanted) {
            log.warn("Orçamento diário de cotações esgotado ({}): pedidas {}, liberadas {} "
                    + "(teto total {}, teto sob demanda {})",
                    purpose, wanted, granted, dailyLimit, onDemandLimit);
        }
        return granted;
    }

    /** Quanto ainda cabe para catálogo e busca — é o número exposto na página. */
    public synchronized int remaining() {
        return remaining(Purpose.ON_DEMAND);
    }

    public synchronized int remaining(Purpose purpose) {
        rolloverIfNeeded();
        return available(purpose);
    }

    private int available(Purpose purpose) {
        int total = Math.max(0, dailyLimit - used);
        if (purpose == Purpose.ON_DEMAND) {
            return Math.min(total, Math.max(0, onDemandLimit - onDemandUsed));
        }
        return total;
    }

    private void rolloverIfNeeded() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(currentDay)) {
            currentDay = today;
            used = 0;
            onDemandUsed = 0;
        }
    }
}
