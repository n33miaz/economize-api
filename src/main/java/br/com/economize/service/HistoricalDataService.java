package br.com.economize.service;

import br.com.economize.dto.HistoricalDataPoint;
import br.com.economize.service.provider.AwesomeApiBudget;
import br.com.economize.service.provider.MarketSnapshotStore;
import br.com.economize.service.provider.NoLiveDataException;
import br.com.economize.service.provider.RecentFallbackCache;
import br.com.economize.service.provider.fallback.BcbSgsClient;
import br.com.economize.service.provider.fallback.CoinGeckoSource;
import br.com.economize.service.provider.fallback.CurrencyNames;
import br.com.economize.service.provider.fallback.FailureSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Série diária de uma moeda ou cripto para o gráfico do app.
 *
 * <p>
 * A fonte primária continua sendo o {@code /daily} da AwesomeAPI — no mesmo
 * shape de sempre ({@code timestamp} em segundos de época, {@code high}). O que
 * mudou: a chamada passa pelo mesmo orçamento diário da Home, e quando ela
 * falha (ou o orçamento acabou) a série vem do SGS do Banco Central para as
 * moedas e da CoinGecko para as cripto, e só depois do snapshot persistido.
 * Falha total devolve lista vazia, como sempre — o gráfico mostra "sem dados",
 * não erro.
 *
 * <p>
 * Cache de 1h por (moeda, janela): série diária não muda mais rápido que isso,
 * e a AwesomeAPI cobra cada consulta da mesma cota que já derruba a Home. O
 * cache guarda SÓ série viva ({@link #liveHistory}); snapshot e lista vazia são
 * aplicados fora dele, a cada chamada — antes uma falha de dez segundos ficava
 * servida como "sem dados" pela hora inteira.
 */
@Slf4j
@Service
public class HistoricalDataService {

    public static final String AWESOME_SOURCE = "AwesomeAPI";

    /**
     * Janelas aceitas na rota. São exatamente as que o app pede (o gráfico usa o
     * padrão de 7 dias; nenhuma versão publicada pediu outra). Cada par novo
     * (código, janela) é entrada nova no cache e uma chamada a mais à AwesomeAPI:
     * com {@code days} livre, {@code ?days=1..120} gastava o dia em dois minutos.
     * Uma janela nova no app entra aqui junto.
     */
    public static final Set<Integer> ALLOWED_DAYS = Set.of(7);

    /** Forma de um código de moeda/cripto do catálogo (USD, DOGE). */
    public static final Pattern CODE = Pattern.compile("^[A-Z]{3,4}$");

    private static final TypeReference<List<HistoricalDataPoint>> POINTS = new TypeReference<>() {
    };
    private static final ZoneId BCB_ZONE = ZoneId.of("America/Sao_Paulo");
    /** O boletim PTAX de fechamento sai por volta das 13h de Brasília. */
    private static final int PTAX_CLOSE_HOUR = 13;

    /** Moeda → série SGS de venda (ver {@link BcbSgsClient}). */
    private static final Map<String, Integer> SGS_SERIES = Map.of(
            "USD", BcbSgsClient.USD_SELL,
            "EUR", BcbSgsClient.EUR_SELL,
            "JPY", BcbSgsClient.JPY_SELL,
            "GBP", BcbSgsClient.GBP_SELL,
            "CHF", BcbSgsClient.CHF_SELL,
            "CNY", BcbSgsClient.CNY_SELL,
            "AUD", BcbSgsClient.AUD_SELL,
            "CAD", BcbSgsClient.CAD_SELL);

    private final WebClient webClient;
    private final String awesomeApiUrl;
    private final AwesomeApiBudget budget;
    private final BcbSgsClient sgs;
    private final CoinGeckoSource coinGecko;
    private final MarketSnapshotStore snapshotStore;
    /**
     * O próprio bean, pelo proxy do Spring: {@link #liveHistory} só passa pelo
     * cache quando chamado de fora — chamado em {@code this}, o proxy é pulado e
     * nada seria cacheado. {@code @Lazy} resolve a auto-referência sem ciclo no
     * boot. Nos testes de unidade (sem Spring) é o próprio objeto.
     */
    private final HistoricalDataService self;
    private final RecentFallbackCache recentFallback;

    public HistoricalDataService(WebClient webClient, String awesomeApiUrl,
            AwesomeApiBudget budget, BcbSgsClient sgs, CoinGeckoSource coinGecko,
            MarketSnapshotStore snapshotStore) {
        this(webClient, awesomeApiUrl, budget, sgs, coinGecko, snapshotStore, RecentFallbackCache.disabled(), null);
    }

    @Autowired
    public HistoricalDataService(WebClient webClient, @Value("${awesome.api.url}") String awesomeApiUrl,
            AwesomeApiBudget budget, BcbSgsClient sgs, CoinGeckoSource coinGecko,
            MarketSnapshotStore snapshotStore, RecentFallbackCache recentFallback,
            @Lazy HistoricalDataService self) {
        this.webClient = webClient;
        this.awesomeApiUrl = awesomeApiUrl;
        this.budget = budget;
        this.sgs = sgs;
        this.coinGecko = coinGecko;
        this.snapshotStore = snapshotStore;
        this.recentFallback = recentFallback;
        this.self = self != null ? self : this;
    }

    /** Sem os prefixos de id do app ({@code currency_}/{@code crypto_}), em caixa alta. */
    public static String normalizeCode(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replace("currency_", "").replace("crypto_", "").toUpperCase(Locale.ROOT);
    }

    /** Código no formato do catálogo E na lista de moedas/cripto que a API conhece. */
    public static boolean isSupportedCode(String code) {
        return code != null && CODE.matcher(code).matches()
                && (CurrencyNames.isKnown(code) || CoinGeckoSource.supports(code));
    }

    /**
     * Série para o gráfico: a viva (do cache, quando houver) ou, sem ela, o
     * snapshot persistido; sem nenhum dos dois, lista vazia. O fallback fica
     * fora do cache de 1 h e só na janela curta de falha
     * ({@link RecentFallbackCache}): com todas as fontes fora, cada abertura
     * do gráfico gastaria mais 1 da fatia diária da AwesomeAPI.
     */
    public Mono<List<HistoricalDataPoint>> getHistoricalData(String currencyCode, int days) {
        String code = normalizeCode(currencyCode);
        if (!isSupportedCode(code) || !ALLOWED_DAYS.contains(days)) {
            return Mono.error(new IllegalArgumentException("Moeda ou janela não suportada no histórico"));
        }
        String snapshotKey = snapshotKey(code, days);
        return recentFallback.liveOrFallback("historical:" + code + ":" + days, () -> self.liveHistory(code, days),
                e -> snapshotStore.lookupPayload(snapshotKey, POINTS)
                        .map(snapshot -> {
                            log.warn("Histórico {} ({}d): fontes indisponíveis; servindo snapshot de {} ({})",
                                    LogSafe.value(code), days, snapshot.savedAt(), snapshot.source());
                            return snapshot.payload();
                        })
                        .defaultIfEmpty(Collections.emptyList()));
    }

    /**
     * Só a série VIVA (AwesomeAPI ou a fonte alternativa), cacheada por 1h.
     * Sem nenhuma das duas, termina com {@link NoLiveDataException} — nunca
     * vazio: erro não entra no cache reativo, e vazio entraria como nulo pelo
     * prazo inteiro. Público só para o proxy de cache; quem quer a série chama
     * {@link #getHistoricalData}. {@code code} já normalizado e validado.
     * {@code sync}: chamadas simultâneas esperam a mesma ida à rede (e gastam
     * 1 do orçamento, não N).
     */
    @Cacheable(value = "historical", sync = true)
    public Mono<List<HistoricalDataPoint>> liveHistory(String code, int days) {
        boolean crypto = CoinGeckoSource.supports(code);
        String snapshotKey = snapshotKey(code, days);
        return fromAwesome(code, days)
                .doOnNext(points -> snapshotStore.savePayload(snapshotKey, points, AWESOME_SOURCE))
                .switchIfEmpty(Mono.defer(() -> fromAlternative(code, days, crypto)
                        .doOnNext(points -> snapshotStore.savePayload(snapshotKey, points,
                                crypto ? CoinGeckoSource.SOURCE : BcbSgsClient.SOURCE))))
                .switchIfEmpty(Mono.error(() -> new NoLiveDataException("histórico " + code)));
    }

    private static String snapshotKey(String code, int days) {
        return MarketSnapshotStore.DATA_PREFIX + "historical:" + code + ":" + days;
    }

    /** AwesomeAPI, dentro do orçamento diário; vazio quando não deu (já logado). */
    private Mono<List<HistoricalDataPoint>> fromAwesome(String code, int days) {
        return Mono.defer(() -> {
            // fatia própria do histórico: a reserva da Home fica intocada
            if (!budget.tryAcquire(AwesomeApiBudget.Purpose.HISTORICAL)) {
                log.warn("Histórico {} ({}d): orçamento diário da AwesomeAPI esgotado; fonte alternativa",
                            LogSafe.value(code),
                        days);
                return Mono.empty();
            }
            return webClient.get()
                    .uri(awesomeApiUrl + String.format("/daily/%s-BRL/%d", code, days))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError,
                            response -> Mono.error(new RuntimeException("Erro API Histórico " + response.statusCode())))
                    .bodyToMono(new ParameterizedTypeReference<List<HistoricalDataPoint>>() {
                    })
                    .filter(points -> !points.isEmpty())
                    .onErrorResume(e -> {
                        log.warn("Histórico {} ({}d): AwesomeAPI falhou ({}); fonte alternativa",
                            LogSafe.value(code), days,
                                FailureSummary.of(e));
                        return Mono.empty();
                    });
        });
    }

    /** CoinGecko para cripto, SGS para moedas com série mapeada; vazio quando não há como. */
    private Mono<List<HistoricalDataPoint>> fromAlternative(String code, int days, boolean crypto) {
        Mono<List<HistoricalDataPoint>> alternative;
        if (crypto) {
            alternative = coinGecko.history(code, days);
        } else {
            Integer seriesId = SGS_SERIES.get(code);
            if (seriesId == null) {
                return Mono.empty();
            }
            alternative = sgs.lastValues(seriesId, days).map(HistoricalDataService::fromSgsPoints);
        }
        return alternative
                .filter(points -> !points.isEmpty())
                .onErrorResume(e -> {
                    log.warn("Histórico {} ({}d): fonte alternativa falhou ({})",
                            LogSafe.value(code), days, FailureSummary.of(e));
                    return Mono.empty();
                });
    }

    /** Série do SGS no shape do /daily: mais recente primeiro, data às 13h de Brasília. */
    static List<HistoricalDataPoint> fromSgsPoints(List<BcbSgsClient.Point> points) {
        List<HistoricalDataPoint> result = new ArrayList<>(points.size());
        for (int i = points.size() - 1; i >= 0; i--) {
            BcbSgsClient.Point point = points.get(i);
            HistoricalDataPoint item = new HistoricalDataPoint();
            item.setTimestamp(String.valueOf(
                    point.date().atTime(PTAX_CLOSE_HOUR, 0).atZone(BCB_ZONE).toEpochSecond()));
            item.setHigh(point.value());
            result.add(item);
        }
        return result;
    }
}
