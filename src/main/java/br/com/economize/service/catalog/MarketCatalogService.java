package br.com.economize.service.catalog;

import br.com.economize.config.MarketCatalogProperties;
import br.com.economize.dto.Indicator;
import br.com.economize.dto.catalog.CatalogItem;
import br.com.economize.dto.catalog.CatalogPage;
import br.com.economize.dto.catalog.CatalogPageInfo;
import br.com.economize.service.IndicatorService;
import br.com.economize.service.provider.MarketSnapshotStore;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Catálogo ampliado com paginação por cursor (EC-099).
 *
 * <p>
 * Duas restrições moldam este serviço, e vale deixar registrado porque elas
 * explicam quase todo o desenho:
 *
 * <ol>
 * <li>A Brapi cobra <b>uma requisição por ticker</b> contra ~1.000/dia. Cotar o
 * catálogo inteiro para poder ordenar por preço/variação custaria a cota de
 * várias horas em uma única abertura de tela. Por isso <b>só a página exibida é
 * cotada</b>, e a ordenação nunca depende de dado que ainda não temos.</li>
 * <li>A ordem precisa ficar parada enquanto o usuário rola. Ela é congelada por
 * janela ({@code rankWindow}) e o cursor carrega a janela em que a rolagem
 * começou, para todas as páginas saírem da mesma ordem.</li>
 * </ol>
 */
@Slf4j
@Service
public class MarketCatalogService {

    private final IndicatorService indicatorService;
    private final MarketCatalogProperties properties;
    private final MarketSnapshotStore snapshotStore;
    private final QuoteBudget quoteBudget;

    /** Cotação fresca de ticker que só existe no catálogo ampliado. */
    private final Cache<String, Indicator> quoteCache;

    /** Ordem congelada por (janela + filtros + favoritos). */
    private final Cache<String, List<String>> frozenOrders;

    public MarketCatalogService(IndicatorService indicatorService, MarketCatalogProperties properties,
            MarketSnapshotStore snapshotStore, QuoteBudget quoteBudget) {
        this.indicatorService = indicatorService;
        this.properties = properties;
        this.snapshotStore = snapshotStore;
        this.quoteBudget = quoteBudget;

        this.quoteCache = Caffeine.newBuilder()
                .expireAfterWrite(properties.getQuoteTtl())
                .maximumSize(600)
                .build();
        // ordem viva por duas janelas: quem começou a rolar no fim de uma janela
        // ainda consegue terminar a lista na mesma ordem
        this.frozenOrders = Caffeine.newBuilder()
                .expireAfterWrite(properties.getRankWindow().multipliedBy(2))
                .maximumSize(200)
                .build();
    }

    public Mono<CatalogPage> page(CatalogQuery query) {
        // uma chamada cacheada cobre moedas inteiras (a AwesomeAPI devolve tudo
        // em /all) e os tickers padrão da Brapi: a primeira página sai sem
        // gastar nada de cota nova
        return indicatorService.getAllIndicators()
                .map(this::indexById)
                .flatMap(baseQuotes -> assemble(query, baseQuotes));
    }

    private Mono<CatalogPage> assemble(CatalogQuery query, Map<String, Indicator> baseQuotes) {
        List<CatalogEntry> universe = buildUniverse(baseQuotes);
        List<CatalogEntry> matched = applyFilters(universe, query);
        Map<String, CatalogEntry> byId = matched.stream()
                .collect(Collectors.toMap(CatalogEntry::id, entry -> entry, (a, b) -> a, LinkedHashMap::new));

        long epoch = currentEpoch();
        CatalogCursor cursor = query.cursor() == null || query.cursor().isBlank()
                ? null
                : CatalogCursor.decode(query.cursor(), query.filterHash());
        long orderEpoch = cursor != null ? cursor.epoch() : epoch;

        List<String> order = frozenOrders.get(
                orderKey(orderEpoch, query),
                key -> rank(matched, query, baseQuotes));

        int start = resumePosition(order, cursor);
        int end = Math.min(start + query.limit(), order.size());
        List<CatalogEntry> pageEntries = order.subList(start, end).stream()
                .map(byId::get)
                .filter(entry -> entry != null)
                .toList();

        return resolvePageQuotes(pageEntries, baseQuotes)
                .map(fetched -> {
                    List<CatalogItem> items = pageEntries.stream()
                            .map(entry -> toItem(entry, baseQuotes, fetched))
                            .toList();

                    CatalogPageInfo info = new CatalogPageInfo();
                    info.setLimit(query.limit());
                    info.setReturned(items.size());
                    info.setTotalMatched(order.size());
                    info.setCatalogVersion(properties.getVersion());
                    info.setRankEpoch(orderEpoch);
                    info.setQuoteBudgetRemaining(quoteBudget.remaining());

                    boolean hasMore = end < order.size();
                    info.setHasMore(hasMore);
                    if (hasMore) {
                        int lastIndex = end - 1;
                        info.setNextCursor(new CatalogCursor(orderEpoch, lastIndex, order.get(lastIndex),
                                query.filterHash()).encode());
                    }
                    return CatalogPage.of(items, info);
                });
    }

    // ---------------------------------------------------------------- universo

    /**
     * Metadado estático da B3 mais o que a AwesomeAPI descobriu sozinha. Quando
     * o mesmo id aparece dos dois lados, o estático manda no metadado (ele tem
     * segmento e relevância) e a cotação vem do agregado.
     */
    private List<CatalogEntry> buildUniverse(Map<String, Indicator> baseQuotes) {
        Map<String, CatalogEntry> universe = new LinkedHashMap<>();

        for (MarketCatalogProperties.Asset asset : properties.getAssets()) {
            String id = indicatorId(asset);
            universe.put(id, new CatalogEntry(id, asset.getType(), asset.getSymbol(), asset.getName(),
                    asset.getSegment(), asset.getRank(), asset.getSymbol()));
        }

        for (Indicator quote : baseQuotes.values()) {
            String id = quote.getId();
            if (id == null || universe.containsKey(id)) {
                continue;
            }
            String type = quote.getType() == null ? "unknown" : quote.getType();
            int rank = properties.getRankOverrides().getOrDefault(id, properties.getDefaultRank());
            // quoteSymbol nulo: já veio cotado no agregado, nunca custa requisição
            universe.put(id, new CatalogEntry(id, type, quote.getCode(), quote.getName(),
                    segmentOf(type), rank, null));
        }

        return List.copyOf(universe.values());
    }

    private String indicatorId(MarketCatalogProperties.Asset asset) {
        // mesma regra que os provedores usam ao montar o id — favoritos do app
        // são gravados por id, então divergir aqui quebraria estrela salva
        return ("index".equals(asset.getType()) ? "index_" : "stock_") + asset.getSymbol();
    }

    private String segmentOf(String type) {
        return switch (type) {
            case "currency" -> "moedas";
            case "crypto" -> "cripto";
            case "index" -> "indices";
            default -> "acoes";
        };
    }

    private List<CatalogEntry> applyFilters(List<CatalogEntry> universe, CatalogQuery query) {
        String search = query.search() == null ? null : fold(query.search());
        return universe.stream()
                .filter(entry -> query.types().isEmpty() || query.types().contains(entry.type()))
                .filter(entry -> query.segments().isEmpty() || query.segments().contains(entry.segment()))
                .filter(entry -> search == null
                        || fold(entry.code()).contains(search)
                        || fold(entry.name()).contains(search))
                .toList();
    }

    // ---------------------------------------------------------------- ordenação

    /**
     * Score de tendência, em números:
     *
     * <pre>
     *   score = (favorito ? favoriteBoost : 0)   // sinal do USUÁRIO
     *         + rank                              // relevância de MERCADO
     *         + min(|variação%|, cap) * peso      // MOMENTO recente
     * </pre>
     *
     * O momento usa módulo porque tombo é tão noticiável quanto alta, e tem teto
     * para um ativo ilíquido com salto de 40% não passar na frente de Ibovespa e
     * dólar. Só entra para quem já está cotado — ordenar por variação o catálogo
     * inteiro exigiria cotar o catálogo inteiro, que é justamente o que a cota
     * da Brapi não permite. O empate cai no id, que é estável.
     */
    private List<String> rank(List<CatalogEntry> matched, CatalogQuery query,
            Map<String, Indicator> baseQuotes) {

        Comparator<CatalogEntry> comparator = switch (query.sort()) {
            case NAME -> Comparator.comparing(CatalogEntry::name, safeCollator())
                    .thenComparing(CatalogEntry::id);
            case CODE -> Comparator.comparing(CatalogEntry::code, safeCollator())
                    .thenComparing(CatalogEntry::id);
            case TRENDING -> Comparator
                    .comparingDouble((CatalogEntry entry) -> trendingScore(entry, query, baseQuotes))
                    .reversed()
                    .thenComparing(CatalogEntry::id);
        };

        return matched.stream().sorted(comparator).map(CatalogEntry::id).toList();
    }

    /**
     * Collator novo a cada ordenação: a classe não é thread-safe e este serviço
     * atende requisições concorrentes.
     */
    private Comparator<String> safeCollator() {
        Collator collator = Collator.getInstance(Locale.forLanguageTag("pt-BR"));
        collator.setStrength(Collator.PRIMARY);
        return (a, b) -> collator.compare(a == null ? "" : a, b == null ? "" : b);
    }

    private double trendingScore(CatalogEntry entry, CatalogQuery query, Map<String, Indicator> baseQuotes) {
        double score = entry.rank();
        if (query.favorites().contains(entry.id())) {
            score += properties.getFavoriteBoost();
        }
        Indicator quoted = baseQuotes.get(entry.id());
        if (quoted == null) {
            quoted = quoteCache.getIfPresent(entry.id());
        }
        if (quoted != null && quoted.getVariation() != null) {
            double variation = Math.abs(quoted.getVariation().doubleValue());
            score += Math.min(variation, properties.getMomentumCap()) * properties.getMomentumWeight();
        }
        return score;
    }

    private long currentEpoch() {
        return System.currentTimeMillis() / properties.getRankWindow().toMillis();
    }

    private String orderKey(long epoch, CatalogQuery query) {
        return epoch + "|" + query.filterHash() + "|" + query.favoritesSignature();
    }

    /**
     * Retoma pelo id do último item entregue, não pelo índice. Se a ordem tiver
     * sido recalculada entre as páginas (cache evictado, por exemplo), o índice
     * antigo apontaria para outro ativo — repetindo um e pulando outro. O índice
     * só entra como plano B, quando o item saiu do catálogo.
     */
    private int resumePosition(List<String> order, CatalogCursor cursor) {
        if (cursor == null) {
            return 0;
        }
        int position = order.indexOf(cursor.lastId());
        int start = position >= 0 ? position + 1 : cursor.index() + 1;
        return Math.max(0, Math.min(start, order.size()));
    }

    // ----------------------------------------------------------------- cotação

    /**
     * Busca cotação só do que está nesta página e ainda não temos. O teto por
     * página segura tanto a cota diária quanto o número de conexões simultâneas
     * ao provedor; o que sobrar volta com o último preço conhecido ou sem preço.
     *
     * <p>
     * O orçamento aqui é CONSULTADO, não reservado: quem debita é o
     * {@code BrapiProvider}, o único ponto por onde as requisições à Brapi
     * saem. Reservar aqui também cobraria duas vezes pela mesma cotação. A
     * consulta serve só para não montar um pedido que já se sabe que não cabe
     * hoje — e se a corrida fizer o provedor liberar menos do que o consultado,
     * os tickers de fora simplesmente voltam sem preço vivo e caem no snapshot
     * stale, sem quarentena e sem erro.
     */
    private Mono<Map<String, Indicator>> resolvePageQuotes(List<CatalogEntry> pageEntries,
            Map<String, Indicator> baseQuotes) {

        int room = Math.min(properties.getMaxQuotesPerPage(), quoteBudget.remaining());
        List<CatalogEntry> toFetch = pageEntries.stream()
                .filter(entry -> entry.quoteSymbol() != null)
                .filter(entry -> !baseQuotes.containsKey(entry.id()))
                .filter(entry -> quoteCache.getIfPresent(entry.id()) == null)
                .limit(Math.max(room, 0))
                .toList();

        if (toFetch.isEmpty()) {
            return Mono.just(Map.<String, Indicator>of());
        }

        String tickers = toFetch.stream().map(CatalogEntry::quoteSymbol).collect(Collectors.joining(","));
        return indicatorService.searchIndicators(tickers)
                .map(quotes -> {
                    Map<String, Indicator> fetched = indexById(quotes);
                    // preço stale também vale a pena guardar: evita repetir o
                    // pedido na próxima página e continua rotulado como stale
                    fetched.forEach(quoteCache::put);
                    return fetched;
                })
                .onErrorResume(e -> {
                    log.warn("Falha ao cotar página do catálogo ({} tickers): {}", toFetch.size(),
                            e.getMessage());
                    return Mono.just(Map.<String, Indicator>of());
                });
    }

    private CatalogItem toItem(CatalogEntry entry, Map<String, Indicator> baseQuotes,
            Map<String, Indicator> fetched) {

        Indicator quote = baseQuotes.get(entry.id());
        if (quote == null) {
            quote = fetched.get(entry.id());
        }
        if (quote == null) {
            quote = quoteCache.getIfPresent(entry.id());
        }
        if (quote == null) {
            // o provedor pode ter servido stale sem passar por aqui; esta é a
            // última tentativa de mostrar ALGUM preço antes de desistir
            quote = staleQuote(entry).orElse(null);
        }
        if (quote != null) {
            // a procedência vem do próprio preço: o que saiu do snapshot chega
            // marcado, e rotular preço velho de LIVE seria mentir para a UI
            String status = quote.isStale() ? CatalogItem.QUOTE_STALE : CatalogItem.QUOTE_LIVE;
            CatalogItem item = CatalogItem.fromQuote(quote, entry.segment(), status);
            if (item.getName() == null || item.getName().isBlank()) {
                item.setName(entry.name());
            }
            return item;
        }

        return CatalogItem.withoutQuote(entry.id(), entry.type(), entry.code(), entry.name(), entry.segment());
    }

    /**
     * Último preço bom guardado pelo provedor. As duas chaves existem porque o
     * BrapiProvider separa snapshot de ticker padrão do de ticker pesquisado.
     */
    private Optional<Indicator> staleQuote(CatalogEntry entry) {
        if (entry.quoteSymbol() == null) {
            return Optional.empty();
        }
        List<String> keys = List.of(
                MarketSnapshotStore.SEARCH_PREFIX + "brapi:" + entry.quoteSymbol(),
                "brapi:" + entry.quoteSymbol());
        for (String key : keys) {
            Optional<Indicator> found = snapshotStore.find(key)
                    .flatMap(list -> list.stream().filter(i -> entry.id().equals(i.getId())).findFirst());
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private Map<String, Indicator> indexById(List<Indicator> indicators) {
        Map<String, Indicator> byId = new HashMap<>();
        for (Indicator indicator : indicators) {
            if (indicator != null && indicator.getId() != null) {
                byId.putIfAbsent(indicator.getId(), indicator);
            }
        }
        return byId;
    }

    // ------------------------------------------------------------------ apoio

    /** Comparação sem acento: "acao" acha "Ação" na busca do catálogo. */
    private String fold(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Entrada do catálogo antes de virar item de resposta. {@code quoteSymbol}
     * nulo marca ativo que já chega cotado no agregado e portanto nunca custa
     * requisição nova.
     */
    private record CatalogEntry(String id, String type, String code, String name, String segment,
            int rank, String quoteSymbol) {
    }

    /** Fatia paginada da lista legada de /all, preservando a ordem de chegada. */
    public static List<Indicator> slice(List<Indicator> indicators, Integer limit, Integer offset) {
        if (limit == null && offset == null) {
            return indicators;
        }
        int from = offset == null ? 0 : offset;
        if (from < 0) {
            throw new IllegalArgumentException("O parâmetro offset não pode ser negativo.");
        }
        if (limit != null && limit <= 0) {
            throw new IllegalArgumentException("O parâmetro limit deve ser maior que zero.");
        }
        if (from >= indicators.size()) {
            return new ArrayList<>();
        }
        int to = limit == null ? indicators.size() : Math.min(from + limit, indicators.size());
        return new ArrayList<>(indicators.subList(from, to));
    }

    /**
     * Semeia o cache de cotação como se a página já tivesse sido cotada antes —
     * usado no teste que verifica que ticket já cotado não gasta cota de novo.
     */
    void primeQuoteCache(String id, Indicator indicator) {
        quoteCache.put(id, indicator);
    }
}
