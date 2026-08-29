package br.com.economize.service.recurrence;

import br.com.economize.service.event.StatementImportedEvent;
import br.com.economize.service.statement.parser.StatementFormat;
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
class RecurrenceDetectionListenerTest {

    @Mock
    private RecurrenceDetectionService detectionService;

    @InjectMocks
    private RecurrenceDetectionListener listener;

    @Test
    void triggersDetectionForTheImportingUser() {
        UUID userId = UUID.randomUUID();
        when(detectionService.detectByUserId(userId))
                .thenReturn(new RecurrenceDetectionService.DetectionSummary(1, 0, 4));

        listener.onStatementImported(new StatementImportedEvent(userId, StatementFormat.OFX, 12, UUID.randomUUID()));

        // a detecção roda em boundedElastic — o listener só agenda e devolve
        verify(detectionService, timeout(2000)).detectByUserId(userId);
    }

    @Test
    void skipsWhenImportBroughtNothingNew() {
        listener.onStatementImported(
                new StatementImportedEvent(UUID.randomUUID(), StatementFormat.CSV, 0, UUID.randomUUID()));

        verifyNoInteractions(detectionService);
    }

    @Test
    void detectionFailureNeverPropagatesToTheImportFlow() {
        UUID userId = UUID.randomUUID();
        when(detectionService.detectByUserId(userId)).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> listener.onStatementImported(
                new StatementImportedEvent(userId, StatementFormat.OFX, 5, UUID.randomUUID())))
                .doesNotThrowAnyException();

        verify(detectionService, timeout(2000)).detectByUserId(userId);
    }
}
