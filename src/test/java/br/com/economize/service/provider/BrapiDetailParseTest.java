package br.com.economize.service.provider;

import br.com.economize.config.MarketCatalogProperties;
import br.com.economize.dto.indicator.AssetDetail;
import br.com.economize.service.catalog.QuoteBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * O parse do detalhe do ativo (EC-103), com a resposta no formato REAL da
 * Brapi: nome longo, faixa de 52 semanas e a série diária com a data em
 * segundos de época, que é como ela chega.
 *
 * <p>Mesmo padrão de dublê do {@link BrapiProviderTest} — WebClient mockado, sem
 * servidor de teste — para não introduzir dependência nova por causa de um
 * arquivo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({ "unchecked", "rawtypes" })
@DisplayName("Detalhe do ativo: parse da resposta da Brapi (EC-103)")
class BrapiDetailParseTest {

    private static final String BASE_URL = "https://example.test/brapi";

    @Mock
    private WebClient webClient;
    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;
    @Mock
    private WebClient.ResponseSpec responseSpec;

    private QuoteBudget budget;
    private BrapiProvider provider;

    private final List<String> requestedUris = new ArrayList<>();
    private final AtomicReference<Object> resposta = new AtomicReference<>();
    private final AtomicReference<RuntimeException> falha = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        budget = new QuoteBudget(new MarketCatalogProperties());
        provider = new BrapiProvider(webClient, BASE_URL, "token-teste",
                new MarketSnapshotStore(), budget);

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenAnswer(inv -> {
            requestedUris.add(inv.getArgument(0));
            return requestHeadersSpec;
        });
        when(requestHeadersSpec.header(anyString(), any(String[].class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenAnswer(inv -> {
            RuntimeException erro = falha.get();
            if (erro != null) return Mono.error(erro);
            return Mono.just(resposta.get());
        });
    }

    /** Época em segundos do início do dia UTC — como a Brapi data cada ponto. */
    private static Map<String, Object> ponto(LocalDate date, double close) {
        return Map.of("date", date.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), "close", close);
    }

    private void responder(Map<String, Object> quote) {
        resposta.set(Map.of("results", List.of(quote)));
    }

    @Test
    @DisplayName("Lê preço, nome da empresa, faixa de 52 semanas e a série")
    void leOsCamposQueImportam() {
        LocalDate hoje = LocalDate.now(ZoneOffset.UTC);
        responder(Map.of(
                "symbol", "PETR4",
                "shortName", "PETR4",
                "longName", "Petroleo Brasileiro SA Pfd",
                "regularMarketPrice", 46.87,
                "regularMarketChangePercent", 4.11,
                "fiftyTwoWeekHigh", 50.69,
                "fiftyTwoWeekLow", 29.31,
                "historicalDataPrice", List.of(
                        ponto(hoje.minusDays(7), 41.45),
                        ponto(hoje.minusDays(30), 43.05))));

        AssetDetail detail = provider.fetchDetail("petr4").block();

        assertThat(detail).isNotNull();
        assertThat(detail.code()).isEqualTo("PETR4");
        // `shortName` vem com o próprio ticker no plano em uso: um título que
        // repete o código não acrescenta nada ao cabeçalho da tela
        assertThat(detail.name()).isEqualTo("Petroleo Brasileiro SA Pfd");
        assertThat(detail.price()).isEqualByComparingTo("46.87");
        assertThat(detail.fiftyTwoWeekHigh()).isEqualByComparingTo("50.69");
        assertThat(detail.fiftyTwoWeekLow()).isEqualByComparingTo("29.31");
        assertThat(detail.rangePosition()).isEqualByComparingTo("0.8213");
        assertThat(detail.stale()).isFalse();
        assertThat(detail.windows()).extracting(AssetDetail.ChangeWindow::key)
                .containsExactly("24h", "7d", "30d", "ytd");
        assertThat(detail.windows().get(1).changePct()).isEqualByComparingTo("13.08");
    }

    @Test
    @DisplayName("Pede a série do ano na MESMA requisição do preço")
    void pedeSerieNaMesmaRequisicao() {
        responder(Map.of("symbol", "PETR4", "regularMarketPrice", 10.0));

        provider.fetchDetail("PETR4").block();

        // Uma requisição, e ela já traz o histórico: uma segunda chamada
        // custaria o dobro da cota diária por detalhe aberto
        assertThat(requestedUris).hasSize(1);
        assertThat(requestedUris.get(0))
                .contains("/quote/PETR4")
                .contains("range=1y")
                .contains("interval=1d")
                // o token vai no header, nunca na URL de log
                .doesNotContain("token");
    }

    @Test
    @DisplayName("Sem orçamento no dia, devolve stale sem gastar requisição")
    void semOrcamentoNaoVaiARede() {
        budget.tryAcquire(Integer.MAX_VALUE, QuoteBudget.Purpose.ON_DEMAND);

        AssetDetail detail = provider.fetchDetail("PETR4").block();

        assertThat(detail).isNotNull();
        assertThat(detail.stale()).isTrue();
        // Sem série não há janela: variação inventada seria pior que ausência
        assertThat(detail.windows()).isEmpty();
        assertThat(requestedUris).isEmpty();
    }

    @Test
    @DisplayName("Papel que o provedor não conhece devolve vazio, e a rota vira 404")
    void desconhecidoDevolveVazio() {
        falha.set(WebClientResponseException.create(404, "Not Found",
                null, null, null));

        assertThat(provider.fetchDetail("XPTO99").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("Papel já sabido inexistente não volta à rede")
    void desconhecidoFicaEmQuarentena() {
        falha.set(WebClientResponseException.create(404, "Not Found", null, null, null));
        provider.fetchDetail("XPTO99").blockOptional();
        int depoisDoPrimeiro = requestedUris.size();

        provider.fetchDetail("XPTO99").blockOptional();

        assertThat(requestedUris).hasSize(depoisDoPrimeiro);
    }

    @Test
    @DisplayName("Falha transitória do provedor não apaga o ativo: volta stale")
    void falhaTransitoriaViraStale() {
        falha.set(WebClientResponseException.create(503, "Service Unavailable",
                null, null, null));

        AssetDetail detail = provider.fetchDetail("PETR4").block();

        assertThat(detail).isNotNull();
        assertThat(detail.stale()).isTrue();
    }

    @Test
    @DisplayName("Resposta sem resultado não estoura: vira stale")
    void respostaVaziaViraStale() {
        resposta.set(Map.of("results", List.of()));

        AssetDetail detail = provider.fetchDetail("PETR4").block();

        assertThat(detail).isNotNull();
        assertThat(detail.stale()).isTrue();
    }

    @Test
    @DisplayName("Ticker em branco não vira requisição")
    void tickerEmBrancoNaoVaiARede() {
        assertThat(provider.fetchDetail("   ").blockOptional()).isEmpty();
        assertThat(provider.fetchDetail(null).blockOptional()).isEmpty();
        assertThat(requestedUris).isEmpty();
    }
}
