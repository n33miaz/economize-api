package br.com.economize.controller;

import br.com.economize.config.MarketCatalogProperties;
import br.com.economize.dto.HistoricalDataPoint;
import br.com.economize.dto.Indicator;
import br.com.economize.dto.catalog.CatalogPage;
import br.com.economize.service.IndicatorService;
import br.com.economize.service.catalog.CatalogQuery;
import br.com.economize.service.catalog.MarketCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/indicators")
@Tag(name = "Indicadores Econômicos", description = "Endpoints para cotações e índices financeiros")
public class IndicatorController {

    // Teto de tickers por chamada de /search. Dez cobre qualquer uso real de
    // tela (o app pede um) e ainda assim limita a quantas requisições uma única
    // chamada pode custar na cota diária do provedor de ações.
    private static final int MAX_SEARCH_TICKERS = 10;

    private final IndicatorService indicatorService;
    private final MarketCatalogService catalogService;
    private final MarketCatalogProperties catalogProperties;

    public IndicatorController(IndicatorService indicatorService, MarketCatalogService catalogService,
            MarketCatalogProperties catalogProperties) {
        this.indicatorService = indicatorService;
        this.catalogService = catalogService;
        this.catalogProperties = catalogProperties;
    }

    @Operation(summary = "Listar todos os indicadores", description = """
            Retorna cotações de moedas e índices (IBOVESPA, etc) atualizados.

            Rota legada, consumida por APK já publicado: sem `limit` e sem `offset` \
            a resposta é exatamente a de sempre (array completo do conjunto padrão, \
            na mesma ordem). Com os parâmetros, devolve a mesma lista fatiada — \
            conveniência de paginação simples, sem cotar nada além do que já vinha. \
            Para o catálogo ampliado com rolagem infinita use GET /indicators/catalog.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista de indicadores"),
            @ApiResponse(responseCode = "400", description = "limit/offset inválidos", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/all")
    public Mono<ResponseEntity<List<Indicator>>> getAllIndicators(
            @Parameter(description = "Quantidade máxima de itens; ausente devolve tudo", example = "20") @RequestParam(required = false) Integer limit,

            @Parameter(description = "Itens a pular antes de começar a fatia", example = "20") @RequestParam(required = false) Integer offset) {

        return indicatorService.getAllIndicators()
                .map(indicators -> MarketCatalogService.slice(indicators, limit, offset))
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "Catálogo paginado de ativos", description = """
            Catálogo ampliado (ações, FIIs, ETFs, BDRs, índices, moedas e cripto) \
            com paginação por cursor, feito para lista infinita.

            Ordenação padrão (`sort=trending`): favoritos do usuário primeiro, depois \
            relevância de mercado, depois variação recente em módulo. A ordem fica \
            congelada por janela e o cursor guarda a janela em que a rolagem começou, \
            então nenhuma página repete ou pula item por causa de reordenação.

            Cotação: só os itens da própria página são cotados, e há teto diário de \
            requisições ao provedor de ações. Item sem preço volta com \
            `quoteStatus=UNQUOTED` e `buy`/`sell` nulos — nunca some da lista.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página do catálogo"),
            @ApiResponse(responseCode = "400", description = "Cursor inválido, cursor de outros filtros, limit <= 0 ou filtro desconhecido", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/catalog")
    public Mono<ResponseEntity<CatalogPage>> getCatalog(
            @Parameter(description = "Tipos separados por vírgula: currency, crypto, stock, index", example = "stock,index") @RequestParam(required = false) String type,

            @Parameter(description = "Segmentos separados por vírgula: acoes, fiis, etfs, bdrs, indices, moedas, cripto", example = "fiis") @RequestParam(required = false) String segment,

            @Parameter(description = "Busca textual em código e nome, sem acento e sem caixa", example = "petro") @RequestParam(required = false) String q,

            @Parameter(description = "Ordenação: trending (padrão), name ou code", example = "trending") @RequestParam(required = false) String sort,

            @Parameter(description = "Itens por página; acima do teto é reduzido ao teto", example = "15") @RequestParam(required = false) Integer limit,

            @Parameter(description = "Ids de favoritos do usuário separados por vírgula (ex.: stock_PETR4,currency_USD). São eles o componente 'do usuário' da ordenação.", example = "stock_PETR4,currency_USD") @RequestParam(required = false) String favorites,

            @Parameter(description = "Cursor opaco devolvido em page.nextCursor; ausente começa do início") @RequestParam(required = false) String cursor) {

        CatalogQuery query = CatalogQuery.of(type, segment, q, sort, limit, favorites, cursor,
                catalogProperties);
        return catalogService.page(query).map(ResponseEntity::ok);
    }

    @Operation(summary = "Buscar ativo específico", description = """
            Busca cotação de um ativo específico (ex: PETR4, AAPL34).

            Aceita mais de um ticker separado por vírgula, até o teto de 10 por \
            chamada — acima disso responde 400. O teto existe porque o provedor \
            de ações cobra UMA requisição por ticker contra a cota diária: sem \
            ele, uma única chamada com centenas de códigos consumiria a cota do \
            dia inteiro e deixaria todo mundo sem cotação. A chamada de ticker \
            único, que é a do app publicado, não muda em nada.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cotações encontradas"),
            @ApiResponse(responseCode = "400", description = "Busca vazia ou acima do teto de tickers", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/search")
    public Mono<ResponseEntity<List<Indicator>>> searchIndicator(@RequestParam String query) {
        return indicatorService.searchIndicators(normalizeSearchQuery(query))
                .map(ResponseEntity::ok);
    }

    /**
     * Teto de tickers por busca e forma canônica do termo. Normalizar aqui (caixa
     * alta, sem espaços, sem repetição) faz "petr4", "PETR4 " e "PETR4,PETR4"
     * caírem todos na mesma entrada do cache curto — e a repetição parar de valer
     * requisição nova ao provedor.
     */
    private String normalizeSearchQuery(String query) {
        List<String> tickers = Arrays.stream(query == null ? new String[0] : query.split(","))
                .map(ticker -> ticker.trim().toUpperCase(Locale.ROOT))
                .filter(ticker -> !ticker.isEmpty())
                .distinct()
                .toList();

        if (tickers.isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos um ativo em query (ex.: PETR4).");
        }
        if (tickers.size() > MAX_SEARCH_TICKERS) {
            throw new IllegalArgumentException(String.format(
                    "Busca limitada a %d ativos por chamada (recebidos: %d). "
                            + "Para listas maiores use GET /indicators/catalog.",
                    MAX_SEARCH_TICKERS, tickers.size()));
        }
        return String.join(",", tickers);
    }

    @Operation(summary = "Dados Históricos", description = "Retorna o histórico de variação de uma moeda nos últimos dias.")
    @GetMapping("/historical/{currencyCode}")
    public Mono<ResponseEntity<List<HistoricalDataPoint>>> getHistoricalData(
            @PathVariable String currencyCode,
            @RequestParam(defaultValue = "7") int days) {
        return indicatorService.getHistoricalData(currencyCode, days)
                .map(ResponseEntity::ok);
    }

    @Operation(summary = "Conversor de Moedas", description = "Converte um valor em Reais (BRL) para a moeda alvo.")
    @GetMapping("/convert")
    public Mono<ResponseEntity<Map<String, Object>>> convertCurrency(
            @RequestParam String code,
            @RequestParam BigDecimal amount) {

        return indicatorService.calculateConversion(code, amount)
                .map(result -> {
                    Map<String, Object> response = Map.of(
                            "currency", code,
                            "amountBrl", amount,
                            "result", result);
                    return ResponseEntity.ok(response);
                })
                .onErrorResume(IllegalArgumentException.class, e -> {
                    Map<String, Object> errorResponse = Map.of("error", e.getMessage());
                    return Mono.just(ResponseEntity.badRequest().body(errorResponse));
                });
    }
}
