package br.com.economize.service.investment;

import br.com.economize.dto.Indicator;
import br.com.economize.service.IndicatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O câmbio que converte posição estrangeira no resumo de investimentos. O que
 * estes testes travam é o lado de FORA do caminho feliz: toda forma de "não
 * tenho cotação" tem de sair como vazio, porque é o vazio que tira a posição
 * dos totais em reais — qualquer número inventado aqui vira patrimônio errado.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExchangeRateLookup — câmbio para o resumo de investimentos")
class ExchangeRateLookupTest {

    @Mock
    private IndicatorService indicatorService;

    private ExchangeRateLookup lookup;

    @BeforeEach
    void setUp() {
        lookup = new ExchangeRateLookup(indicatorService);
    }

    @Test
    @DisplayName("usa o buy do indicador currency_<MOEDA>, o mesmo dólar da Home")
    void shouldReadBuyFromTheCurrencyIndicator() {
        when(indicatorService.getAllIndicators()).thenReturn(Mono.just(List.of(
                indicator("stock_PETR4", "38.10"),
                indicator("currency_EUR", "6.10"),
                indicator("currency_USD", "5.42"))));

        assertThat(lookup.brlPerUnit("USD")).contains(new BigDecimal("5.42"));
        // o código chega como o provedor escreveu
        assertThat(lookup.brlPerUnit(" usd ")).contains(new BigDecimal("5.42"));
        assertThat(lookup.brlPerUnit("EUR")).contains(new BigDecimal("6.10"));
    }

    @Test
    @DisplayName("moeda que a Home não acompanha não tem câmbio — e não empresta o do dólar")
    void unknownCurrencyIsEmpty() {
        when(indicatorService.getAllIndicators()).thenReturn(Mono.just(List.of(indicator("currency_USD", "5.42"))));

        assertThat(lookup.brlPerUnit("CHF")).isEmpty();
    }

    @Test
    @DisplayName("cotação nula ou zerada não é cotação")
    void nullOrZeroBuyIsEmpty() {
        when(indicatorService.getAllIndicators()).thenReturn(Mono.just(List.of(
                indicator("currency_USD", null), indicator("currency_EUR", "0"))));

        assertThat(lookup.brlPerUnit("USD")).isEmpty();
        assertThat(lookup.brlPerUnit("EUR")).isEmpty();
    }

    @Test
    @DisplayName("provedor fora do ar ou sem resposta vira vazio, nunca erro no resumo")
    void failureIsEmpty() {
        when(indicatorService.getAllIndicators())
                .thenReturn(Mono.error(new IllegalStateException("provedor fora")))
                .thenReturn(Mono.empty());

        assertThat(lookup.brlPerUnit("USD")).isEmpty();
        assertThat(lookup.brlPerUnit("USD")).isEmpty();
    }

    @Test
    @DisplayName("moeda em branco não consulta nada")
    void blankCurrencyIsEmpty() {
        assertThat(lookup.brlPerUnit(null)).isEmpty();
        assertThat(lookup.brlPerUnit("  ")).isEmpty();
        verify(indicatorService, never()).getAllIndicators();
    }

    @Test
    @DisplayName("chamado de dentro do event loop responde vazio em vez de bloquear a thread")
    void neverBlocksANonBlockingThread() {
        // Schedulers.parallel() é thread não bloqueante, como as do Netty: um
        // block() ali lança IllegalStateException. O vazio é o caminho tratado
        Optional<BigDecimal> rate = Mono.fromCallable(() -> lookup.brlPerUnit("USD"))
                .subscribeOn(Schedulers.parallel())
                .block(Duration.ofSeconds(5));

        assertThat(rate).isEmpty();
        verify(indicatorService, never()).getAllIndicators();
    }

    private static Indicator indicator(String id, String buy) {
        Indicator indicator = new Indicator();
        indicator.setId(id);
        indicator.setBuy(buy == null ? null : new BigDecimal(buy));
        return indicator;
    }
}
