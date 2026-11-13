package br.com.economize.service;

import br.com.economize.config.CacheConfig;
import br.com.economize.config.MarketSourcesProperties;
import br.com.economize.service.macro.ForeignQuoteService;
import br.com.economize.service.macro.MacroIndicatorService;
import br.com.economize.service.macro.TreasuryService;
import br.com.economize.service.provider.AwesomeApiBudget;
import br.com.economize.service.provider.MarketSnapshotStore;
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
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * do provedor tem de fazer a chamada seguinte ir à rede de novo; sucesso tem de
 * fazer a seguinte NÃO ir.
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
        final List<String> urls = new ArrayList<>();
        final Set<String> down = new HashSet<>();

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
            return StubWebClient.respondingWith(transport.urls, transport::respond);
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

    @Test
    @DisplayName("Histórico: falha de todas as fontes não fica no cache; série viva fica")
    void historicalFailureMustNotBeCached() {
        transport.down.add("/sgs/");

        StepVerifier.create(historical.getHistoricalData("USD", 7))
                .assertNext(points -> assertTrue(points.isEmpty())).verifyComplete();
        StepVerifier.create(historical.getHistoricalData("USD", 7))
                .assertNext(points -> assertTrue(points.isEmpty())).verifyComplete();
        assertEquals(2, transport.count("/sgs/"), "a segunda chamada tenta a rede de novo");

        transport.down.clear();
        StepVerifier.create(historical.getHistoricalData("USD", 7))
                .assertNext(points -> assertEquals(2, points.size())).verifyComplete();
        StepVerifier.create(historical.getHistoricalData("USD", 7))
                .assertNext(points -> assertEquals(2, points.size())).verifyComplete();
        assertEquals(3, transport.count("/sgs/"), "série viva sai do cache na quarta chamada");
    }

    @Test
    @DisplayName("Macro: lista parcial (uma série fora) não fica no cache; completa fica")
    void macroPartialMustNotBeCached() {
        transport.down.add("bcdata.sgs.189/");

        StepVerifier.create(macro.getMacroIndicators())
                .assertNext(items -> assertEquals(6, items.size())).verifyComplete();
        StepVerifier.create(macro.getMacroIndicators())
                .assertNext(items -> assertEquals(6, items.size())).verifyComplete();
        assertEquals(14, transport.count("/sgs/"), "parcial: as 7 séries pedidas de novo");

        transport.down.clear();
        StepVerifier.create(macro.getMacroIndicators())
                .assertNext(items -> assertEquals(7, items.size())).verifyComplete();
        StepVerifier.create(macro.getMacroIndicators())
                .assertNext(items -> assertEquals(7, items.size())).verifyComplete();
        assertEquals(21, transport.count("/sgs/"), "completa: a quarta chamada sai do cache");
    }

    @Test
    @DisplayName("Macro: SGS inteiro fora não fica no cache")
    void macroTotalFailureMustNotBeCached() {
        transport.down.add("/sgs/");

        StepVerifier.create(macro.getMacroIndicators())
                .assertNext(items -> assertTrue(items.isEmpty())).verifyComplete();
        StepVerifier.create(macro.getMacroIndicators())
                .assertNext(items -> assertTrue(items.isEmpty())).verifyComplete();

        assertEquals(14, transport.count("/sgs/"));
    }

    @Test
    @DisplayName("Tesouro: lista vazia do fallback não fica no cache; a viva fica")
    void treasuryFailureMustNotBeCached() {
        transport.down.add(".csv");

        StepVerifier.create(treasury.getBonds()).assertNext(bonds -> assertTrue(bonds.isEmpty())).verifyComplete();
        StepVerifier.create(treasury.getBonds()).assertNext(bonds -> assertTrue(bonds.isEmpty())).verifyComplete();
        assertEquals(2, transport.count(".csv"), "a segunda chamada tenta a rede de novo");

        transport.down.clear();
        StepVerifier.create(treasury.getBonds()).assertNext(bonds -> assertFalse(bonds.isEmpty())).verifyComplete();
        StepVerifier.create(treasury.getBonds()).assertNext(bonds -> assertFalse(bonds.isEmpty())).verifyComplete();
        assertEquals(3, transport.count(".csv"), "lista viva sai do cache na quarta chamada");
    }

    @Test
    @DisplayName("Cotação estrangeira: fontes fora não ficam no cache; a viva fica")
    void foreignQuoteFailureMustNotBeCached() {
        transport.down.add("/yahoo/");

        StepVerifier.create(foreign.getQuote("VT", "US")).verifyComplete();
        StepVerifier.create(foreign.getQuote("VT", "US")).verifyComplete();
        assertEquals(2, transport.count("/yahoo/"), "a segunda chamada tenta a rede de novo");

        transport.down.clear();
        StepVerifier.create(foreign.getQuote("VT", "US")).expectNextCount(1).verifyComplete();
        StepVerifier.create(foreign.getQuote("VT", "US")).expectNextCount(1).verifyComplete();
        assertEquals(3, transport.count("/yahoo/"), "cotação viva sai do cache na quarta chamada");
    }
}
