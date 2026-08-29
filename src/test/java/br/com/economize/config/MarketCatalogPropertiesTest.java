package br.com.economize.config;

import br.com.economize.service.provider.BrapiProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guardas do catálogo versionado. Ele é editado à mão a cada rodada de
 * manutenção, e erro de digitação aqui não estoura em lugar nenhum: vira ativo
 * duplicado na lista, segmento que nenhum filtro alcança ou ticker que só
 * queima cota da Brapi sem nunca devolver preço.
 */
class MarketCatalogPropertiesTest {

    /** Cota diária do plano da Brapi que o projeto contrata. */
    private static final int PLAN_QUOTA = 1000;

    private final MarketCatalogProperties properties = new MarketCatalogProperties();

    @Test
    @DisplayName("Nenhum ticker pode aparecer duas vezes no catálogo")
    void symbolsMustBeUnique() {
        Set<String> seen = new HashSet<>();
        List<String> duplicated = properties.getAssets().stream()
                .map(MarketCatalogProperties.Asset::getSymbol)
                .filter(symbol -> !seen.add(symbol))
                .toList();

        assertTrue(duplicated.isEmpty(), "tickers repetidos no catálogo: " + duplicated);
    }

    @Test
    @DisplayName("Todo ativo deve ter tipo, segmento e nome preenchidos")
    void everyAssetMustBeComplete() {
        Set<String> validTypes = Set.of("stock", "index");
        Set<String> validSegments = Set.of("acoes", "fiis", "etfs", "bdrs", "indices");

        properties.getAssets().forEach(asset -> {
            assertTrue(asset.getSymbol() != null && !asset.getSymbol().isBlank(),
                    "ticker vazio no catálogo");
            assertTrue(asset.getName() != null && !asset.getName().isBlank(),
                    "nome vazio para " + asset.getSymbol());
            assertTrue(validTypes.contains(asset.getType()),
                    "tipo inválido em " + asset.getSymbol() + ": " + asset.getType());
            assertTrue(validSegments.contains(asset.getSegment()),
                    "segmento inválido em " + asset.getSymbol() + ": " + asset.getSegment());
            assertTrue(asset.getRank() >= 0 && asset.getRank() <= 100,
                    "relevância fora de 0..100 em " + asset.getSymbol());
        });
    }

    @Test
    @DisplayName("Índice deve vir com o prefixo que a Brapi espera")
    void indexSymbolsMustKeepProviderPrefix() {
        properties.getAssets().stream()
                .filter(asset -> "index".equals(asset.getType()))
                .forEach(asset -> assertTrue(asset.getSymbol().startsWith("^"),
                        "índice sem '^' não é aceito pela Brapi: " + asset.getSymbol()));
    }

    @Test
    @DisplayName("Catálogo deve ser grande o bastante para a lista não acabar na cara do usuário")
    void catalogMustBeLargeEnoughForInfiniteScroll() {
        int pages = properties.getAssets().size() / properties.getDefaultPageSize();

        assertTrue(pages >= 8, "catálogo pequeno demais: só " + pages + " páginas de ativos da B3");
    }

    @Test
    @DisplayName("Orçamento diário deve deixar folga para /all e para a busca do usuário")
    void dailyBudgetMustLeaveHeadroom() {
        // Aritmética da cota, com os números que a produção usa hoje:
        //
        //   /all (Home) : uma recarga custa defaultTickerCount() requisições e
        //                 acontece no máximo uma vez por TTL do cache
        //                 → 24h / 30min = 48 recargas × 7 tickers = 336/dia
        //   catálogo+busca ................................ = dailyQuoteBudget
        //   teto total de tudo ......................... = dailyProviderBudget
        //   cota do plano Brapi ...................................... ~1.000
        //
        // As três asserções abaixo são o que impede a conta de voltar a fechar
        // no vermelho: foi assim que o /all sozinho passou a consumir 1.008/dia
        // (TTL de 10 min) e derrubou a Home de todo mundo até a virada do dia.
        long homeRefreshesPerDay = Duration.ofDays(1).toMinutes() / CacheConfig.INDICATORS_TTL.toMinutes();
        long worstCaseHome = homeRefreshesPerDay * BrapiProvider.defaultTickerCount();
        long worstCaseTotal = worstCaseHome + properties.getDailyQuoteBudget();

        assertTrue(worstCaseTotal <= properties.getDailyProviderBudget(),
                "pior caso (" + worstCaseTotal + ") não cabe no teto total ("
                        + properties.getDailyProviderBudget() + ")");

        assertTrue(properties.getDailyProviderBudget() <= PLAN_QUOTA - PLAN_QUOTA / 10,
                "teto total precisa deixar ao menos 10% de folga na cota do plano");

        assertTrue(properties.getDailyProviderBudget() - properties.getDailyQuoteBudget() >= worstCaseHome,
                "a reserva da Home (" + (properties.getDailyProviderBudget() - properties.getDailyQuoteBudget())
                        + ") precisa cobrir o pior caso do /all (" + worstCaseHome
                        + "), senão rolagem profunda deixa o 'Mercado agora' sem preço");

        assertTrue(properties.getMaxQuotesPerPage() <= properties.getMaxPageSize(),
                "não faz sentido cotar mais tickers do que cabe numa página");
    }

    @Test
    @DisplayName("Relevância de moedas deve cobrir as que a Home destaca")
    void currencyRanksMustCoverTheMainOnes() {
        assertEquals(99, properties.getRankOverrides().get("currency_USD"));
        assertTrue(properties.getRankOverrides().containsKey("currency_EUR"));
        assertTrue(properties.getRankOverrides().containsKey("crypto_BTC"));
    }
}
