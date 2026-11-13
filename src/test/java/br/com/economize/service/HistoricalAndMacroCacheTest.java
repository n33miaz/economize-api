package br.com.economize.service;

import br.com.economize.config.CacheConfig;
import br.com.economize.config.MarketSourcesProperties;
import br.com.economize.service.macro.ForeignQuoteService;
import br.com.economize.service.macro.MacroIndicatorService;
import br.com.economize.service.macro.TreasuryService;
import br.com.economize.service.provider.AwesomeApiBudget;
import br.com.economize.service.provider.MarketSnapshotStore;
import br.com.economize.service.provider.RecentFallbackCache;
import br.com.economize.service.provider.fallback.BcbSgsClient;
import br.com.economize.service.provider.fallback.CoinGeckoSource;
import br.com.economize.support.StubWebClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O cache reativo de verdade (proxy do Spring + Caffeine assíncrono do
 * {@link CacheConfig}) guardando SÓ resultado vivo.
 *
 * <p>
 * Teste de unidade com {@code new} não prova nada aqui: sem o proxy não há
 * cache, e o defeito era justamente o que o cache guardava — o fallback
 * (snapshot velho ou lista vazia) ficava servido pelo prazo inteiro (1 h a 6 h)
 * depois de uma falha de segundos. O transporte é falso e conta as URLs: falha
 * do provedor fica só na janela curta ({@link RecentFallbackCache}) — dentro
 * dela a chamada seguinte não vai à rede, e vencida (aqui, esvaziada à mão) vai
 * de novo; sucesso fica no cache longo; e chamadas simultâneas com o cache
 * vazio fazem UMA ida ao provedor ({@code @Cacheable(sync = true)}).
 */
@SpringJUnitConfig(HistoricalAndMacroCacheTest.Config.class)
@TestPropertySource(properties = "awesome.api.url=https://example.test/awesome")
// contexto novo por teste: o snapshot em memória de um teste não pode virar o
// fallback do outro
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class HistoricalAndMacroCacheTest {

    private static final String SGS_POINTS = "[{\"data\":\"03/09/2026\",\"valor\":\"5.0962\"},"
            + "{\"data\":\"04/09/2026\",\"valor\":\"5.1253\"}]";
    private static final String CSV = "Tipo Titulo;Data Vencimento;Data Base;Taxa Compra Manha;"
            + "Taxa Venda Manha;PU Compra Manha;PU Venda Manha;PU Base Manha\n"
            + "Tesouro Selic;01/03/2029;04/09/2026;0,03;0,04;19810,47;19795,28;19795,28\n";
    private static final String YAHOO = "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":120.5,"
            + "\"chartPreviousClose\":119.0,\"currency\":\"EUR\",\"regularMarketTime\":1788557375}}]}}";

    /** O "mundo lá fora": quais hosts estão de pé e o que foi pedido. */
    static final class Transport {
        final List<String> urls = new CopyOnWriteArrayList<>();
        final Set<String> down = new HashSet<>();
        /** Latência da resposta: abre a janela em que chamadas simultâneas se sobrepõem. */
        volatile Duration latency = Duration.ZERO;

        ClientResponse respond(ClientRequest request) {
            String url = request.url().toString();
            if (down.stream().anyMatch(url::contains)) {
                return StubWebClient.status(HttpStatus.SERVICE_UNAVAILABLE, "");
            }
            if (url.contains("/sgs/")) {
                return StubWebClient.json(SGS_POINTS);
            }
            if (url.endsWith(".csv")) {
                return StubWebClient.text("text/csv", CSV);
            }
            if (url.contains("/yahoo/")) {
                return StubWebClient.json(YAHOO);
            }
            // AwesomeAPI, JSON oficial do Tesouro e Stooq: fora, a não ser que o
            // teste diga o contrário — é o cenário de produção de hoje
            return StubWebClient.status(HttpStatus.SERVICE_UNAVAILABLE, "");
        }

        long count(String fragment) {
            return urls.stream().filter(url -> url.contains(fragment)).count();
        }
    }

    @Configuration
    @EnableCaching
    @Import({ CacheConfig.class, HistoricalDataService.class, MacroIndicatorService.class, TreasuryService.class,
            ForeignQuoteService.class })
    static class Config {

        @Bean
        Transport transport() {
            return new Transport();
        }

        @Bean
        WebClient webClient(Transport transport) {
            return StubWebClient.of(transport.urls,
                    request -> Mono.delay(transport.latency).map(ignored -> transport.respond(request)));
        }

        @Bean
        RecentFallbackCache recentFallbackCache() {
            return new RecentFallbackCache(Duration.ofMinutes(2));
        }

        @Bean
        MarketSourcesProperties marketSourcesProperties() {
            MarketSourcesProperties properties = new MarketSourcesProperties();
            properties.setBcbSgsUrl("https://example.test/sgs");
            properties.setCoingeckoUrl("https://example.test/coingecko");
            properties.setTreasuryUrl("https://example.test/tesouro/oficial.json");
            properties.setTreasuryCsvUrl("https://example.test/tesouro/taxas.csv");
            properties.setYahooUrl("https://example.test/yahoo");
            properties.setStooqUrl("https://example.test/stooq");
            return properties;
        }

        @Bean
        MarketSnapshotStore marketSnapshotStore() {
            return new MarketSnapshotStore();
        }

        @Bean
        AwesomeApiBudget awesomeApiBudget(MarketSourcesProperties properties) {
            return new AwesomeApiBudget(properties);
        }

        @Bean
        BcbSgsClient bcbSgsClient(WebClient webClient, MarketSourcesProperties properties) {
            return new BcbSgsClient(webClient, properties);
        }

        @Bean
        CoinGeckoSource coinGeckoSource(WebClient webClient, MarketSourcesProperties properties) {
            return new CoinGeckoSource(webClient, properties);
        }

        /** Só a conversão em BRL da cotação em USD usa; aqui a cotação é em EUR. */
        @Bean
        IndicatorService indicatorService() {
            return Mockito.mock(IndicatorService.class);
        }
    }

    @Autowired
    private Transport transport;
    @Autowired
    private CacheManager cacheManager;
    @Autowired
    private RecentFallbackCache recentFallback;
    @Autowired
    private HistoricalDataService historical;
    @Autowired
    private MacroIndicatorService macro;
    @Autowired
    private TreasuryService treasury;
    @Autowired
    private ForeignQuoteService foreign;

    @BeforeEach
    void reset() {
        transport.urls.clear();
        transport.down.clear();
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    /** A janela curta de falha venceu: o fallback guardado deixa de valer. */
    private void windowExpires() {
        recentFallback.invalidateAll();
    }

    @Test
    @DisplayName("Histórico: falha fica só na janela curta (não gasta mais orçamento); série viva fica no cache")
    void historicalFailureStaysOnlyInShortWindow() {
        transport.down.add("/sgs/");

        StepVerifier.create(historical.getHistoricalData("USD", 7))
                .assertNext(points -> assertTrue(points.isEmpty())).verifyComplete();
        StepVerifier.create(historical.getHistoricalData("USD", 7))
                .assertNext(points -> assertTrue(points.isEmpty())).verifyComplete();
        assertEquals(1, transport.count("/awesome/"), "dentro da janela não gasta outra chamada da fatia");
        assertEquals(1, transport.count("/sgs/"));

        windowExpires();
        transport.down.clear();
        StepVerifier.create(historical.getHistoricalData("USD", 7))
                .assertNext(points -> assertEquals(2, points.size())).verifyComplete();
        StepVerifier.create(historical.getHistoricalData("USD", 7))
                .assertNext(points -> assertEquals(2, points.size())).verifyComplete();
        assertEquals(2, transport.count("/sgs/"), "vencida a janela tenta de novo; a série viva vem do cache");
    }

    @Test
    @DisplayName("Macro: com uma série fora, a segunda abertura na janela não refaz as 7 chamadas")
    void macroPartialStaysOnlyInShortWindow() {
        transport.down.add("bcdata.sgs.189/");

        StepVerifier.create(macro.getMacroIndicators())
                .assertNext(items -> assertEquals(6, items.size())).verifyComplete();
        StepVerifier.create(macro.getMacroIndicators())
                .assertNext(items -> assertEquals(6, items.size())).verifyComplete();
        assertEquals(7, transport.count("/sgs/"), "a segunda abertura sai da janela curta");

        windowExpires();
        transport.down.clear();
        StepVerifier.create(macro.getMacroIndicators())
                .assertNext(items -> assertEquals(7, items.size())).verifyComplete();
        StepVerifier.create(macro.getMacroIndicators())
                .assertNext(items -> assertEquals(7, items.size())).verifyComplete();
        assertEquals(14, transport.count("/sgs/"), "parcial não ficou no cache de 6 h; a completa fica");
    }

    @Test
    @DisplayName("Macro: aberturas simultâneas com o cache vazio fazem UMA ida ao SGS")
    void macroConcurrentCallsShareOneFetch() {
        transport.latency = Duration.ofMillis(300);

        List<List<?>> results = Mono.zip(macro.getMacroIndicators(), macro.getMacroIndicators(),
                macro.getMacroIndicators()).map(t -> List.<List<?>>of(t.getT1(), t.getT2(), t.getT3())).block();

        assertEquals(3, results.size());
        assertEquals(7, transport.count("/sgs/"), "três chamadas, sete séries pedidas uma vez só");
    }

    @Test
    @DisplayName("Tesouro: falha fica só na janela curta; a lista viva fica no cache")
    void treasuryFailureStaysOnlyInShortWindow() {
        transport.down.add(".csv");

        StepVerifier.create(treasury.getBonds()).assertNext(bonds -> assertTrue(bonds.isEmpty())).verifyComplete();
        StepVerifier.create(treasury.getBonds()).assertNext(bonds -> assertTrue(bonds.isEmpty())).verifyComplete();
        assertEquals(1, transport.count(".csv"), "dentro da janela não vai à rede");

        windowExpires();
        transport.down.clear();
        StepVerifier.create(treasury.getBonds()).assertNext(bonds -> assertFalse(bonds.isEmpty())).verifyComplete();
        StepVerifier.create(treasury.getBonds()).assertNext(bonds -> assertFalse(bonds.isEmpty())).verifyComplete();
        assertEquals(2, transport.count(".csv"), "vencida a janela tenta de novo; a lista viva vem do cache");
    }

    @Test
    @DisplayName("Tesouro: chamadas simultâneas fazem uma ida ao provedor")
    void treasuryConcurrentCallsShareOneFetch() {
        transport.latency = Duration.ofMillis(300);

        Mono.zip(treasury.getBonds(), treasury.getBonds()).block();

        assertEquals(1, transport.count(".csv"));
    }

    @Test
    @DisplayName("Cotação estrangeira: símbolo desconhecido não volta à rede na janela; a viva fica no cache")
    void unknownForeignSymbolStaysOnlyInShortWindow() {
        transport.down.add("/yahoo/");

        StepVerifier.create(foreign.getQuote("VT", "US")).verifyComplete();
        StepVerifier.create(foreign.getQuote("VT", "US")).verifyComplete();
        assertEquals(1, transport.count("/yahoo/"), "o segundo pedido não vai ao Yahoo");
        assertEquals(1, transport.count("/stooq"), "nem ao Stooq");

        windowExpires();
        transport.down.clear();
        StepVerifier.create(foreign.getQuote("VT", "US")).expectNextCount(1).verifyComplete();
        StepVerifier.create(foreign.getQuote("VT", "US")).expectNextCount(1).verifyComplete();
        assertEquals(2, transport.count("/yahoo/"), "vencida a janela tenta de novo; a viva vem do cache");
    }
}
