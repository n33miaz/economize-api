package br.com.economize.service;

import br.com.economize.service.event.StatementImportedEvent;
import br.com.economize.service.statement.parser.StatementFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatementHygieneListenerTest {

    @Mock
    private StatementHygieneService hygieneService;

    // Cadência de verdade: é ela que decide entre total e janela
    private HygieneCadence cadence;
    private StatementHygieneListener listener;

    @BeforeEach
    void setUp() {
        cadence = new HygieneCadence(java.time.Duration.ofHours(24));
        listener = new StatementHygieneListener(hygieneService, cadence);
    }

    private static final java.time.OffsetDateTime PRIMEIRA =
            java.time.OffsetDateTime.parse("2026-06-10T09:00:00Z");
    private static final java.time.OffsetDateTime ULTIMA =
            java.time.OffsetDateTime.parse("2026-06-12T18:00:00Z");

    /** A marca muda numa thread do boundedElastic: espera curta, sem biblioteca. */
    private void aguarda(java.util.function.BooleanSupplier condicao) throws InterruptedException {
        long limite = System.currentTimeMillis() + 2000;
        while (!condicao.getAsBoolean() && System.currentTimeMillis() < limite) Thread.sleep(10);
        org.assertj.core.api.Assertions.assertThat(condicao.getAsBoolean()).isTrue();
    }

    @Test
    @DisplayName("todo arquivo importado passa pela faxina")
    void shouldRunHygieneForTheImportingUser() {
        UUID userId = UUID.randomUUID();
        when(hygieneService.runFor(userId))
                .thenReturn(new StatementHygieneService.Outcome(2, 4, 1, 3, 1, 1, 0));

        listener.onStatementImported(
                new StatementImportedEvent(userId, StatementFormat.OFX, 12, UUID.randomUUID()));

        // roda em boundedElastic — o listener só agenda e devolve
        verify(hygieneService, timeout(2000)).runFor(userId);
    }

    @Test
    @DisplayName("importação que diz as datas é limpa só na janela delas")
    void shouldRunHygieneOnlyOverTheImportedWindow() {
        UUID userId = UUID.randomUUID();
        java.time.OffsetDateTime primeira = PRIMEIRA;
        java.time.OffsetDateTime ultima = ULTIMA;
        // já houve varredura total hoje: o histórico está assentado
        cadence.fullSweepDone(userId);

        listener.onStatementImported(new StatementImportedEvent(
                userId, StatementFormat.PLUGGY, 3, UUID.randomUUID(), primeira, ultima));

        verify(hygieneService, timeout(2000)).runFor(userId, new HygieneWindow(primeira, ultima));
        // e NÃO a varredura do histórico inteiro, que é o que este caminho
        // deixou de fazer
        verify(hygieneService, org.mockito.Mockito.never()).runFor(userId);
    }

    @Test
    @DisplayName("arquivo repetido não traz linha nova, então não há o que limpar")
    void shouldSkipWhenImportBroughtNothingNew() {
        listener.onStatementImported(
                new StatementImportedEvent(UUID.randomUUID(), StatementFormat.CSV, 0, UUID.randomUUID()));

        verifyNoInteractions(hygieneService);
    }

    @Test
    @DisplayName("faxina que falha nunca desfaz a importação")
    void shouldNeverPropagateFailureToTheImportFlow() {
        UUID userId = UUID.randomUUID();
        when(hygieneService.runFor(userId)).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> listener.onStatementImported(
                new StatementImportedEvent(userId, StatementFormat.OFX, 5, UUID.randomUUID())))
                .doesNotThrowAnyException();

        verify(hygieneService, timeout(2000)).runFor(userId);
    }

    @Test
    @DisplayName("a primeira importação do intervalo varre tudo; as seguintes, só a janela")
    void shouldRunFullSweepOncePerInterval() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        StatementImportedEvent evento = new StatementImportedEvent(
                userId, StatementFormat.PLUGGY, 3, UUID.randomUUID(), PRIMEIRA, ULTIMA);

        listener.onStatementImported(evento);
        verify(hygieneService, timeout(2000)).runFor(userId);
        // a marca só entra quando a total TERMINA
        aguarda(() -> !cadence.fullSweepDue(userId));

        listener.onStatementImported(evento);
        verify(hygieneService, timeout(2000)).runFor(userId, new HygieneWindow(PRIMEIRA, ULTIMA));
        verify(hygieneService, org.mockito.Mockito.times(1)).runFor(userId);
    }

    @Test
    @DisplayName("faxina em janela que falha devolve a próxima para a varredura total")
    void shouldFallBackToFullSweepAfterAFailure() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        cadence.fullSweepDone(userId);
        HygieneWindow janela = new HygieneWindow(PRIMEIRA, ULTIMA);
        when(hygieneService.runFor(userId, janela)).thenThrow(new IllegalStateException("boom"));
        StatementImportedEvent evento = new StatementImportedEvent(
                userId, StatementFormat.PLUGGY, 3, UUID.randomUUID(), PRIMEIRA, ULTIMA);

        listener.onStatementImported(evento);
        aguarda(() -> cadence.fullSweepDue(userId));

        listener.onStatementImported(evento);
        verify(hygieneService, timeout(2000)).runFor(userId);
    }

    @Test
    @DisplayName("sincronização que só carimbou conta em linha antiga também passa pela faxina")
    void shouldRunWhenOnlyOldRowsWereTouched() {
        UUID userId = UUID.randomUUID();
        cadence.fullSweepDone(userId);

        // zero linhas novas, mas com datas: houve linha antiga alterada
        listener.onStatementImported(new StatementImportedEvent(
                userId, StatementFormat.PLUGGY, 0, UUID.randomUUID(), PRIMEIRA, ULTIMA));

        verify(hygieneService, timeout(2000)).runFor(userId, new HygieneWindow(PRIMEIRA, ULTIMA));
    }
}
