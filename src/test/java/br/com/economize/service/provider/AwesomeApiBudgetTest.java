package br.com.economize.service.provider;

import br.com.economize.model.MarketSnapshot;
import br.com.economize.repository.MarketSnapshotRepository;
import br.com.economize.support.MutableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AwesomeApiBudgetTest {

    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");

    @Mock
    private MarketSnapshotRepository repository;

    @Test
    @DisplayName("Histórico para na fatia dele; a Home ainda usa a reserva inteira")
    void historicalMustNotEatHomeReserve() {
        AwesomeApiBudget budget = new AwesomeApiBudget(5, 3, new MutableClock(NOW), null);

        assertTrue(budget.tryAcquire(AwesomeApiBudget.Purpose.HISTORICAL));
        assertTrue(budget.tryAcquire(AwesomeApiBudget.Purpose.HISTORICAL));
        assertFalse(budget.tryAcquire(AwesomeApiBudget.Purpose.HISTORICAL), "5 - 3 = 2 para o histórico");

        assertEquals(3, budget.remaining(AwesomeApiBudget.Purpose.HOME));
        assertTrue(budget.tryAcquire());
        assertTrue(budget.tryAcquire());
        assertTrue(budget.tryAcquire());
        assertFalse(budget.tryAcquire(), "o teto total manda para todos");
    }

    @Test
    @DisplayName("A Home gastando o teto também fecha o histórico")
    void homeExhaustingTotalClosesHistorical() {
        AwesomeApiBudget budget = new AwesomeApiBudget(2, 1, new MutableClock(NOW), null);

        assertTrue(budget.tryAcquire());
        assertTrue(budget.tryAcquire());

        assertEquals(0, budget.remaining(AwesomeApiBudget.Purpose.HISTORICAL));
        assertFalse(budget.tryAcquire(AwesomeApiBudget.Purpose.HISTORICAL));
    }

    @Test
    @DisplayName("Uso retomado no boot: reinício não devolve o teto do dia")
    void usageShouldSurviveRestart() {
        when(repository.findById("data:budget:awesome:2026-11-12")).thenReturn(Optional.of(new MarketSnapshot(
                "data:budget:awesome:2026-11-12", "{\"used\":4,\"historicalUsed\":2}",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), "budget")));
        BudgetUsageStore usage = new BudgetUsageStore(repository, new ObjectMapper(), new MutableClock(NOW),
                Duration.ofHours(1));

        AwesomeApiBudget budget = new AwesomeApiBudget(5, 3, new MutableClock(NOW), usage);
        budget.restore();

        assertEquals(1, budget.remaining());
        assertEquals(0, budget.remaining(AwesomeApiBudget.Purpose.HISTORICAL), "a fatia de 2 já foi gasta");
    }

    @Test
    @DisplayName("Banco fora do ar no boot: conta do zero, sem derrubar nada")
    void unreadableUsageShouldStartFromZero() {
        when(repository.findById(any())).thenThrow(new RuntimeException("connection refused"));
        BudgetUsageStore usage = new BudgetUsageStore(repository, new ObjectMapper(), new MutableClock(NOW),
                Duration.ofHours(1));

        AwesomeApiBudget budget = new AwesomeApiBudget(5, 3, new MutableClock(NOW), usage);
        budget.restore();

        assertEquals(5, budget.remaining());
    }

    @Test
    @DisplayName("Rajada de chamadas vira UMA gravação, com o valor do fim da rajada")
    void burstShouldCoalesceIntoOneWrite() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        BudgetUsageStore usage = new BudgetUsageStore(repository, new ObjectMapper(), new MutableClock(NOW),
                Duration.ofMillis(200));
        AwesomeApiBudget budget = new AwesomeApiBudget(100, 0, new MutableClock(NOW), usage);

        for (int i = 0; i < 20; i++) {
            assertTrue(budget.tryAcquire());
        }

        ArgumentCaptor<MarketSnapshot> saved = ArgumentCaptor.forClass(MarketSnapshot.class);
        verify(repository, timeout(3000)).save(saved.capture());
        verify(repository, after(500).times(1)).save(any());
        assertEquals("data:budget:awesome:2026-11-12", saved.getValue().getKey());
        assertTrue(saved.getValue().getPayload().contains("\"used\":20"), saved.getValue().getPayload());
    }

    @Test
    @DisplayName("Libera até o teto e recusa depois")
    void shouldGrantUpToLimit() {
        AwesomeApiBudget budget = new AwesomeApiBudget(2, new MutableClock(Instant.parse("2026-09-06T12:00:00Z")));

        assertTrue(budget.tryAcquire());
        assertTrue(budget.tryAcquire());
        assertFalse(budget.tryAcquire(), "a terceira chamada do dia não pode sair");
        assertEquals(0, budget.remaining());
        assertEquals(2, budget.limit());
    }

    @Test
    @DisplayName("Vira com o dia UTC, que é como o provedor conta")
    void shouldResetOnUtcDayChange() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-06T23:30:00Z"));
        AwesomeApiBudget budget = new AwesomeApiBudget(1, clock);

        assertTrue(budget.tryAcquire());
        assertFalse(budget.tryAcquire());

        clock.advance(Duration.ofMinutes(45));

        assertEquals(1, budget.remaining(), "meia-noite UTC devolve o orçamento");
        assertTrue(budget.tryAcquire());
    }
}
