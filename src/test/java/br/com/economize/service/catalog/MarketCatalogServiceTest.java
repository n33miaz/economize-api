package br.com.economize.service.catalog;

import br.com.economize.config.MarketCatalogProperties;
import br.com.economize.dto.Indicator;
import br.com.economize.dto.catalog.CatalogItem;
import br.com.economize.dto.catalog.CatalogPage;
import br.com.economize.service.IndicatorService;
import br.com.economize.service.provider.MarketSnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketCatalogServiceTest {

    @Mock
    private IndicatorService indicatorService;

    private MarketCatalogProperties properties;
    private MarketSnapshotStore snapshotStore;
    private MarketCatalogService service;

    /** Tickers efetivamente pedidos ao provedor, em ordem de chamada. */
    private final List<String> requestedTickers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        properties = catalogProperties();
        snapshotStore = new MarketSnapshotStore();
        service = new MarketCatalogService(indicatorService, properties, snapshotStore,
                new QuoteBudget(properties));

        requestedTickers.clear();
        when(indicatorService.getAllIndicators()).thenReturn(Mono.just(List.of(currency("USD", "Dólar", 0))));
        when(indicatorService.searchIndicators(anyString())).thenAnswer(invocation -> {
            String csv = invocation.getArgument(0);
            requestedTickers.add(csv);
            List<Indicator> quotes = Arrays.stream(csv.split(","))
                    .map(ticker -> stockQuote(ticker, new BigDecimal("10.00"), new BigDecimal("1.00")))
                    .toList();
            return Mono.just(quotes);
        });
    }

    // ------------------------------------------------------------- paginação

    @Test
    @DisplayName("Primeira página respeita o limite e devolve cursor de continuação")
    void firstPageShouldRespectLimitAndOfferCursor() {
        CatalogPage page = page(query(null, null, null, null, null, null, null));

        assertEquals(2, page.getItems().size());
        assertEquals(2, page.getPage().getLimit());
        assertEquals(2, page.getPage().getReturned());
        assertEquals(6, page.getPage().getTotalMatched());
        assertTrue(page.getPage().isHasMore());
        assertNotNull(page.getPage().getNextCursor());
        assertEquals(properties.getVersion(), page.getPage().getCatalogVersion());
    }

    @Test
    @DisplayName("Rolagem completa não repete nem pula item e termina sem cursor")
    void fullScrollShouldCoverEveryItemExactlyOnce() {
        List<String> visited = new ArrayList<>();
        String cursor = null;
        CatalogPage page;

        do {
            page = page(query(null, null, null, null, null, null, cursor));
            page.getItems().forEach(item -> visited.add(item.getId()));
            cursor = page.getPage().getNextCursor();
        } while (page.getPage().isHasMore());

        assertEquals(List.of("currency_USD", "stock_AAA3", "stock_BBB3", "stock_CCC3", "stock_DDD11",
                "stock_EEE3"), visited);
        assertNull(page.getPage().getNextCursor(), "última página não pode oferecer continuação");
        assertFalse(page.getPage().isHasMore());
    }

    @Test
    @DisplayName("Cursor além do fim devolve página vazia em vez de erro")
    void cursorPastTheEndShouldReturnEmptyPage() {
        CatalogQuery base = query(null, null, null, null, null, null, null);
        String cursor = new CatalogCursor(System.currentTimeMillis() / properties.getRankWindow().toMillis(),
                999, "stock_INEXISTENTE", base.filterHash()).encode();

        CatalogPage page = page(query(null, null, null, null, null, null, cursor));

        assertTrue(page.getItems().isEmpty());
        assertEquals(0, page.getPage().getReturned());
        assertFalse(page.getPage().isHasMore());
        assertNull(page.getPage().getNextCursor());
    }

    @Test
    @DisplayName("Cursor corrompido deve virar erro de requisição inválida")
    void invalidCursorShouldFail() {
        CatalogQuery invalid = query(null, null, null, null, null, null, "cursor@quebrado!!");

        assertThrows(IllegalArgumentException.class, () -> service.page(invalid).block());
    }

    @Test
    @DisplayName("Cursor emitido para outros filtros não pode ser reaproveitado")
    void cursorFromAnotherFilterShouldFail() {
        String cursor = page(query(null, null, null, null, null, null, null))
                .getPage().getNextCursor();

        CatalogQuery outraConsulta = query("stock", null, null, null, null, null, cursor);

        assertThrows(IllegalArgumentException.class, () -> service.page(outraConsulta).block());
    }

    @Test
    @DisplayName("Limite acima do teto é reduzido ao teto, não rejeitado")
    void limitAboveCapShouldBeClamped() {
        CatalogPage page = page(query(null, null, null, null, 500, null, null));

        assertEquals(properties.getMaxPageSize(), page.getPage().getLimit());
        assertEquals(properties.getMaxPageSize(), page.getPage().getReturned());
    }

    @Test
    @DisplayName("Limite zero ou negativo é erro do cliente")
    void nonPositiveLimitShouldFail() {
        assertThrows(IllegalArgumentException.class,
                () -> query(null, null, null, null, 0, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> query(null, null, null, null, -1, null, null));
    }

    // ------------------------------------------------------------- ordenação

    @Test
    @DisplayName("Ordem congelada: variação nova no meio da rolagem não reordena a lista")
    void orderShouldStayFrozenWhileScrolling() {
        CatalogPage first = page(query(null, null, null, null, null, null, null));
        assertEquals(List.of("currency_USD", "stock_AAA3"), ids(first));

        // CCC3 dispara 12%: recalculada, a ordem colocaria CCC3 (70 + 10*2 = 90)
        // à frente de BBB3 (80) e a segunda página sairia trocada
        when(indicatorService.getAllIndicators()).thenReturn(Mono.just(List.of(
                currency("USD", "Dólar", 0),
                stockQuote("CCC3", new BigDecimal("9.00"), new BigDecimal("12.00")))));

        CatalogPage second = page(query(null, null, null, null, null, null,
                first.getPage().getNextCursor()));

        assertEquals(List.of("stock_BBB3", "stock_CCC3"), ids(second));
        assertEquals(first.getPage().getRankEpoch(), second.getPage().getRankEpoch());
    }

    @Test
    @DisplayName("Favoritos do usuário sobem ao topo sem tirar ninguém da lista")
    void favoritesShouldRankFirst() {
        CatalogPage page = page(query(null, null, null, null, 3, "stock_EEE3,stock_DDD11", null));

        assertEquals(List.of("stock_DDD11", "stock_EEE3", "currency_USD"), ids(page));
        assertEquals(6, page.getPage().getTotalMatched());
    }

    @Test
    @DisplayName("Momento do mercado desempata em favor de quem se moveu mais")
    void marketMomentumShouldOutrankStaticRelevance() {
        // CCC3 (rank 70) com 12% de variação passa BBB3 (rank 80) parado:
        // 70 + min(12,10)*2 = 90 contra 80
        when(indicatorService.getAllIndicators()).thenReturn(Mono.just(List.of(
                currency("USD", "Dólar", 0),
                stockQuote("CCC3", new BigDecimal("5.00"), new BigDecimal("12.00")))));

        CatalogPage page = page(query("stock", null, null, null, 3, null, null));

        assertEquals(List.of("stock_AAA3", "stock_CCC3", "stock_BBB3"), ids(page));
    }

    @Test
    @DisplayName("Ordenação alfabética é determinística e ignora acento")
    void nameSortShouldBeDeterministic() {
        CatalogPage page = page(query(null, null, null, "name", 3, null, null));

        assertEquals(List.of("stock_AAA3", "stock_BBB3", "stock_CCC3"), ids(page));
    }

    @Test
    @DisplayName("Ordenação desconhecida é erro do cliente")
    void unknownSortShouldFail() {
        assertThrows(IllegalArgumentException.class,
                () -> query(null, null, null, "gainers", null, null, null));
    }

    // ---------------------------------------------------------------- filtros

    @Test
    @DisplayName("Filtro por segmento restringe a lista e o total")
    void segmentFilterShouldNarrowResults() {
        CatalogPage page = page(query(null, "fiis", null, null, null, null, null));

        assertEquals(List.of("stock_DDD11"), ids(page));
        assertEquals(1, page.getPage().getTotalMatched());
        assertFalse(page.getPage().isHasMore());
    }

    @Test
    @DisplayName("Busca textual acha sem acento e sem caixa")
    void searchShouldIgnoreAccentAndCase() {
        CatalogPage page = page(query(null, null, "acucar", null, null, null, null));

        assertEquals(List.of("stock_CCC3"), ids(page));
    }

    @Test
    @DisplayName("Filtro sem resultado devolve página vazia coerente")
    void emptyResultShouldBeCoherent() {
        CatalogPage page = page(query(null, null, "nao-existe-esse-ativo", null, null, null, null));

        assertTrue(page.getItems().isEmpty());
        assertEquals(0, page.getPage().getTotalMatched());
        assertFalse(page.getPage().isHasMore());
        assertNull(page.getPage().getNextCursor());
    }

    @Test
    @DisplayName("Tipo e segmento desconhecidos são erro do cliente")
    void unknownFiltersShouldFail() {
        assertThrows(IllegalArgumentException.class,
                () -> query("acao", null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> query(null, "cryptobolha", null, null, null, null, null));
    }

    // ------------------------------------------------------------------ cota

    @Test
    @DisplayName("Só os tickers da página exibida são cotados")
    void shouldQuoteOnlyThePageBeingShown() {
        page(query("stock", null, null, null, 2, null, null));

        assertEquals(List.of("AAA3,BBB3"), requestedTickers,
                "cotar além da página queimaria a cota diária da Brapi");
    }

    @Test
    @DisplayName("Ativo já cotado na janela não gasta requisição nova")
    void cachedQuoteShouldNotSpendBudget() {
        service.primeQuoteCache("stock_AAA3", stockQuote("AAA3", new BigDecimal("7.00"), BigDecimal.ZERO));

        CatalogPage page = page(query("stock", null, null, null, 1, null, null));

        assertEquals(List.of("stock_AAA3"), ids(page));
        assertEquals(CatalogItem.QUOTE_LIVE, page.getItems().get(0).getQuoteStatus());
        verify(indicatorService, never()).searchIndicators(anyString());
    }

    @Test
    @DisplayName("Sem orçamento o item continua na lista, só que sem preço")
    void exhaustedBudgetShouldDegradeToUnquoted() {
        properties.setDailyQuoteBudget(0);
        service = new MarketCatalogService(indicatorService, properties, snapshotStore,
                new QuoteBudget(properties));

        CatalogPage page = page(query("stock", null, null, null, 1, null, null));

        CatalogItem item = page.getItems().get(0);
        assertEquals("stock_AAA3", item.getId());
        assertEquals(CatalogItem.QUOTE_UNQUOTED, item.getQuoteStatus());
        assertNull(item.getBuy());
        assertEquals("Alfa ON", item.getName(), "sem preço, a identidade ainda vem do catálogo");
        assertEquals(0, page.getPage().getQuoteBudgetRemaining());
        verify(indicatorService, never()).searchIndicators(anyString());
    }

    @Test
    @DisplayName("Sem orçamento, o último preço bom conhecido é servido como STALE")
    void exhaustedBudgetShouldServeStaleSnapshot() {
        snapshotStore.save(MarketSnapshotStore.SEARCH_PREFIX + "brapi:AAA3",
                List.of(stockQuote("AAA3", new BigDecimal("31.50"), new BigDecimal("2.00"))));
        properties.setDailyQuoteBudget(0);
        service = new MarketCatalogService(indicatorService, properties, snapshotStore,
                new QuoteBudget(properties));

        CatalogItem item = page(query("stock", null, null, null, 1, null, null)).getItems().get(0);

        assertEquals(CatalogItem.QUOTE_STALE, item.getQuoteStatus());
        assertEquals(new BigDecimal("31.50"), item.getBuy());
    }

    @Test
    @DisplayName("Preço que o provedor serviu stale não pode ser anunciado como LIVE")
    void staleQuoteFromProviderShouldNotBeLive() {
        // o provedor degrada para o último snapshot bom quando a Brapi falha ou
        // quando a cota do dia acabou; a lista continua cheia, mas o preço é
        // velho — e dizer LIVE aqui seria mentir para a UI
        when(indicatorService.searchIndicators(anyString())).thenAnswer(invocation -> Mono.just(
                List.of(stockQuote("AAA3", new BigDecimal("12.00"), new BigDecimal("1.00")).staleCopy())));

        CatalogItem item = page(query("stock", null, null, null, 1, null, null)).getItems().get(0);

        assertEquals(CatalogItem.QUOTE_STALE, item.getQuoteStatus());
        assertEquals(new BigDecimal("12.00"), item.getBuy(), "preço velho ainda é melhor que nenhum");
    }

    @Test
    @DisplayName("Ticker que voltou sem preço continua sendo pedido: quarentena é decisão do provedor")
    void missingQuoteShouldNotBeQuarantinedByTheCatalog() {
        // ausência de preço não distingue "ativo não existe" de "provedor caiu"
        // ou "cota acabou": quarentenar aqui apagaria por horas um ativo vivo
        // doAnswer, e não when(...): reprogramar com when executaria o stub
        // anterior e sujaria a lista de tickers pedidos
        doAnswer(invocation -> {
            requestedTickers.add(invocation.getArgument(0));
            return Mono.just(List.<Indicator>of());
        }).when(indicatorService).searchIndicators(anyString());

        CatalogItem primeira = page(query("stock", null, null, null, 1, null, null)).getItems().get(0);
        page(query("stock", null, null, null, 1, null, null));

        assertEquals(CatalogItem.QUOTE_UNQUOTED, primeira.getQuoteStatus());
        assertEquals(List.of("AAA3", "AAA3"), requestedTickers);
    }

    @Test
    @DisplayName("Moeda descoberta pelo provedor entra no catálogo já cotada")
    void discoveredCurrencyShouldComeQuoted() {
        CatalogItem usd = page(query("currency", null, null, null, 1, null, null)).getItems().get(0);

        assertEquals("currency_USD", usd.getId());
        assertEquals("moedas", usd.getSegment());
        assertEquals(CatalogItem.QUOTE_LIVE, usd.getQuoteStatus());
        verify(indicatorService, never()).searchIndicators(anyString());
    }

    // -------------------------------------------------------- fatia da /all

    @Test
    @DisplayName("Fatia da rota legada: sem parâmetro devolve a lista inteira")
    void sliceWithoutParametersShouldReturnEverything() {
        List<Indicator> all = List.of(currency("USD", "Dólar", 0), currency("EUR", "Euro", 0));

        assertEquals(all, MarketCatalogService.slice(all, null, null));
    }

    @Test
    @DisplayName("Fatia da rota legada respeita limite, deslocamento e o fim da lista")
    void sliceShouldRespectBounds() {
        List<Indicator> all = List.of(currency("USD", "Dólar", 0), currency("EUR", "Euro", 0),
                currency("GBP", "Libra", 0));

        assertEquals(1, MarketCatalogService.slice(all, 1, null).size());
        assertEquals("EUR", MarketCatalogService.slice(all, 1, 1).get(0).getCode());
        assertEquals(2, MarketCatalogService.slice(all, 5, 1).size());
        assertTrue(MarketCatalogService.slice(all, 5, 99).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> MarketCatalogService.slice(all, 0, null));
        assertThrows(IllegalArgumentException.class, () -> MarketCatalogService.slice(all, 1, -1));
    }

    // ------------------------------------------------------------------ apoio

    private CatalogPage page(CatalogQuery query) {
        CatalogPage page = service.page(query).block();
        assertNotNull(page);
        return page;
    }

    private CatalogQuery query(String type, String segment, String q, String sort, Integer limit,
            String favorites, String cursor) {
        return CatalogQuery.of(type, segment, q, sort, limit, favorites, cursor, properties);
    }

    private List<String> ids(CatalogPage page) {
        return page.getItems().stream().map(Indicator::getId).toList();
    }

    private MarketCatalogProperties catalogProperties() {
        MarketCatalogProperties properties = new MarketCatalogProperties();
        properties.setDefaultPageSize(2);
        properties.setMaxPageSize(3);
        properties.setDailyQuoteBudget(50);
        properties.setAssets(List.of(
                MarketCatalogProperties.Asset.of("AAA3", "Alfa ON", "acoes", 90),
                MarketCatalogProperties.Asset.of("BBB3", "Beta ON", "acoes", 80),
                MarketCatalogProperties.Asset.of("CCC3", "Cia Açúcar ON", "acoes", 70),
                MarketCatalogProperties.Asset.of("DDD11", "Delta FII", "fiis", 60),
                MarketCatalogProperties.Asset.of("EEE3", "Epsilon ON", "acoes", 50)));
        Map<String, Integer> ranks = new LinkedHashMap<>();
        ranks.put("currency_USD", 95);
        properties.setRankOverrides(ranks);
        return properties;
    }

    private Indicator currency(String code, String name, double variation) {
        Indicator indicator = new Indicator();
        indicator.setId("currency_" + code);
        indicator.setType("currency");
        indicator.setCode(code);
        indicator.setName(name);
        indicator.setBuy(new BigDecimal("5.00"));
        indicator.setVariation(BigDecimal.valueOf(variation));
        return indicator;
    }

    private Indicator stockQuote(String ticker, BigDecimal price, BigDecimal variation) {
        Indicator indicator = new Indicator();
        indicator.setId("stock_" + ticker);
        indicator.setType("stock");
        indicator.setCode(ticker);
        indicator.setName(ticker + " S.A.");
        indicator.setBuy(price);
        indicator.setVariation(variation);
        return indicator;
    }
}
