package br.com.economize.service.recurrence;

import br.com.economize.service.event.StatementImportedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Dispara a detecção de recorrência depois de cada importação bem-sucedida,
 * best-effort: o {@code @EventListener} roda síncrono no publisher in-memory,
 * então o método só AGENDA a varredura no boundedElastic e devolve o controle
 * na hora — o upload não espera a detecção, e falha aqui nunca desfaz a
 * importação (que já foi commitada quando o evento é publicado).
 *
 * <p>Com o profile "rabbit" ativo os eventos vão para o broker em vez do
 * ApplicationEventPublisher e este listener fica mudo — o gatilho manual
 * {@code POST /api/v1/recurrences/detect} continua disponível.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecurrenceDetectionListener {

    private final RecurrenceDetectionService detectionService;

    @EventListener
    public void onStatementImported(StatementImportedEvent event) {
        // importação duplicada/vazia não traz transação nova — nada a aprender
        if (event.getTransactionsImported() <= 0) return;
        Mono.fromCallable(() -> detectionService.detectByUserId(event.getUserId()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        summary -> log.info(
                                "Recorrência pós-importação: {} séries novas, {} atualizadas, {} vínculos, user={}",
                                summary.seriesCreated(), summary.seriesUpdated(),
                                summary.linksCreated(), event.getUserId()),
                        error -> log.warn("Detecção de recorrência pós-importação falhou (best-effort): {}",
                                error.getMessage()));
    }
}
