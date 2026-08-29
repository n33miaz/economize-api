package br.com.economize.service.catalog;

import br.com.economize.config.MarketCatalogProperties;
import br.com.economize.service.catalog.QuoteBudget.Purpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuoteBudgetTest {

    @Test
    @DisplayName("Deve liberar até o teto e zerar depois de esgotado")
    void shouldGrantUpToLimit() {
        QuoteBudget budget = new QuoteBudget(propertiesWithBudget(5));

        assertEquals(3, budget.tryAcquire(3, Purpose.ON_DEMAND));
        assertEquals(2, budget.remaining());
        assertEquals(2, budget.tryAcquire(4, Purpose.ON_DEMAND),
                "concessão parcial: metade da página cotada vale mais que nenhuma");
        assertEquals(0, budget.tryAcquire(1, Purpose.ON_DEMAND));
        assertEquals(0, budget.remaining());
    }

    @Test
    @DisplayName("Pedido não positivo não consome orçamento")
    void shouldIgnoreNonPositiveRequests() {
        QuoteBudget budget = new QuoteBudget(propertiesWithBudget(5));

        assertEquals(0, budget.tryAcquire(0, Purpose.ON_DEMAND));
        assertEquals(0, budget.tryAcquire(-3, Purpose.ON_DEMAND));
        assertEquals(5, budget.remaining());
    }

    @Test
    @DisplayName("Orçamento deve voltar ao teto na virada do dia")
    void shouldResetOnNewDay() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-15T20:00:00Z"));
        QuoteBudget budget = new QuoteBudget(propertiesWithBudget(4), clock);

        assertEquals(4, budget.tryAcquire(4, Purpose.ON_DEMAND));
        assertEquals(0, budget.remaining());

        clock.advance(Duration.ofDays(1));

        assertEquals(4, budget.remaining());
        assertEquals(4, budget.tryAcquire(4, Purpose.ON_DEMAND));
    }

    @Test
    @DisplayName("Rolagem infinita não pode consumir a cota reservada à Home")
    void onDemandMustNotStarveHome() {
        // teto total 10, dos quais no máximo 4 para catálogo e busca
        QuoteBudget budget = new QuoteBudget(properties(10, 4));

        assertEquals(4, budget.tryAcquire(50, Purpose.ON_DEMAND), "demanda aberta para no seu próprio teto");
        assertEquals(0, budget.tryAcquire(1, Purpose.ON_DEMAND));

        // a reserva da Home continua intacta: 10 - 4 = 6
        assertEquals(6, budget.remaining(Purpose.HOME));
        assertEquals(6, budget.tryAcquire(6, Purpose.HOME));
        assertEquals(0, budget.remaining(Purpose.HOME));
    }

    @Test
    @DisplayName("A Home consumindo tudo também fecha a demanda aberta — o teto total manda")
    void totalCeilingMustBindEveryPurpose() {
        QuoteBudget budget = new QuoteBudget(properties(6, 500));

        assertEquals(6, budget.tryAcquire(6, Purpose.HOME));

        assertEquals(0, budget.remaining(), "sem teto total, a busca continuaria gastando a cota do plano");
        assertEquals(0, budget.tryAcquire(1, Purpose.ON_DEMAND));
    }

    @Test
    @DisplayName("Virada do dia devolve os dois níveis, não só o total")
    void rolloverMustResetBothLevels() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-15T20:00:00Z"));
        QuoteBudget budget = new QuoteBudget(properties(10, 4), clock);

        assertEquals(4, budget.tryAcquire(9, Purpose.ON_DEMAND));

        clock.advance(Duration.ofDays(1));

        assertEquals(4, budget.remaining());
        assertEquals(10, budget.remaining(Purpose.HOME));
    }

    private MarketCatalogProperties propertiesWithBudget(int budget) {
        // teto total folgado: aqui o que se testa é o teto da demanda aberta
        return properties(Integer.MAX_VALUE, budget);
    }

    private MarketCatalogProperties properties(int total, int onDemand) {
        MarketCatalogProperties properties = new MarketCatalogProperties();
        properties.setDailyProviderBudget(total);
        properties.setDailyQuoteBudget(onDemand);
        return properties;
    }

    /** Relógio controlável: sem ele, testar a virada do dia exigiria esperar. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            this.instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
