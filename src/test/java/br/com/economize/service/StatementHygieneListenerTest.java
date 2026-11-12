package br.com.economize.service;

import br.com.economize.service.event.StatementImportedEvent;
import br.com.economize.service.statement.parser.StatementFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

    @InjectMocks
    private StatementHygieneListener listener;

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
        java.time.OffsetDateTime primeira = java.time.OffsetDateTime.parse("2026-06-10T09:00:00Z");
        java.time.OffsetDateTime ultima = java.time.OffsetDateTime.parse("2026-06-12T18:00:00Z");

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
}
