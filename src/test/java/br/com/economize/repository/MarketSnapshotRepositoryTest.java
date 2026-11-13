package br.com.economize.repository;

import br.com.economize.model.MarketSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A tabela da V25 contra banco: a coluna se chama {@code key}, palavra
 * reservada no H2 — se a aspa do mapeamento sumir, é aqui que aparece.
 */
@DataJpaTest
@DisplayName("Snapshot de mercado persistido (V25)")
class MarketSnapshotRepositoryTest {

    @Autowired
    private MarketSnapshotRepository repository;

    @Test
    @DisplayName("Grava por chave e a segunda gravação da mesma chave atualiza em vez de duplicar")
    void shouldUpsertByKey() {
        OffsetDateTime savedAt = OffsetDateTime.of(2026, 9, 6, 12, 0, 0, 0, ZoneOffset.UTC);
        repository.saveAndFlush(new MarketSnapshot("awesome:all", "[{\"code\":\"USD\"}]", savedAt, "AwesomeAPI"));

        MarketSnapshot found = repository.findById("awesome:all").orElseThrow();
        assertEquals("[{\"code\":\"USD\"}]", found.getPayload());
        assertEquals(savedAt.toInstant(), found.getSavedAt().toInstant());
        assertEquals("AwesomeAPI", found.getSource());

        repository.saveAndFlush(new MarketSnapshot("awesome:all", "[]", savedAt.plusHours(1), "Frankfurter (BCE)"));

        assertEquals(1, repository.count(), "chave é chave primária: nunca duas linhas");
        MarketSnapshot updated = repository.findById("awesome:all").orElseThrow();
        assertEquals("[]", updated.getPayload());
        assertEquals("Frankfurter (BCE)", updated.getSource());
    }

    @Test
    @DisplayName("O payload é TEXT: um /all inteiro (dezenas de KB) cabe sem truncar")
    void payloadShouldHoldLargeJson() {
        String big = "[" + "{\"code\":\"USD\",\"buy\":5.1249}".repeat(5000) + "]";
        repository.saveAndFlush(new MarketSnapshot("data:treasury", big,
                OffsetDateTime.now(ZoneOffset.UTC), "Tesouro Transparente"));

        assertEquals(big.length(), repository.findById("data:treasury").orElseThrow().getPayload().length());
    }

    @Test
    @DisplayName("Limpeza apaga num DELETE só o que é mais velho que o corte, e nada mais")
    void shouldDeleteOnlyRowsOlderThanCutoff() {
        OffsetDateTime now = OffsetDateTime.of(2026, 11, 12, 12, 0, 0, 0, ZoneOffset.UTC);
        repository.saveAndFlush(new MarketSnapshot("search:brapi:OLD1", "[]", now.minusDays(8), "Brapi"));
        repository.saveAndFlush(new MarketSnapshot("data:historical:USD:7", "[]", now.minusDays(30), "AwesomeAPI"));
        repository.saveAndFlush(new MarketSnapshot("awesome:all", "[]", now.minusDays(1), "AwesomeAPI"));
        repository.saveAndFlush(new MarketSnapshot("data:budget:brapi:2026-11-12", "{}", now, "budget"));

        int removed = repository.deleteSavedBefore(now.minusDays(7));

        assertEquals(2, removed);
        assertEquals(List.of("awesome:all", "data:budget:brapi:2026-11-12"),
                repository.findAll().stream().map(MarketSnapshot::getKey).sorted().toList());
    }

    @Test
    @DisplayName("Hidratação só traz lista de cotação que ainda vale: sem data: e sem vencido")
    void shouldFindOnlyHydratableRows() {
        OffsetDateTime now = OffsetDateTime.of(2026, 11, 12, 12, 0, 0, 0, ZoneOffset.UTC);
        repository.saveAndFlush(new MarketSnapshot("awesome:all", "[]", now.minusHours(2), "AwesomeAPI"));
        repository.saveAndFlush(new MarketSnapshot("brapi:PETR4", "[]", now.minusDays(8), "Brapi"));
        repository.saveAndFlush(new MarketSnapshot("data:macro", "[]", now.minusHours(1), "BCB"));
        repository.saveAndFlush(new MarketSnapshot("data:budget:awesome:2026-11-12", "{}", now, "budget"));

        List<MarketSnapshot> rows = repository.findHydratable(now.minusDays(7), "data:%");

        assertEquals(List.of("awesome:all"), rows.stream().map(MarketSnapshot::getKey).toList());
    }
}
