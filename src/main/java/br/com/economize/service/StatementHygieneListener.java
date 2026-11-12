package br.com.economize.service;

import br.com.economize.service.event.StatementImportedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

/**
 * Dispara a faxina depois de cada importação bem-sucedida, best-effort: o
 * {@code @EventListener} roda síncrono no publisher in-memory, então o método
 * só AGENDA o trabalho no boundedElastic e devolve o controle na hora — o
 * upload não espera, e falha aqui nunca desfaz a importação (que já foi
 * commitada quando o evento é publicado).
 *
 * <p>Este listener substituiu o que só disparava a detecção de recorrência. O
 * motivo é de ordem: a detecção tem de rodar DEPOIS das marcas, senão um Pix do
 * dono para ele mesmo vira "despesa mensal" e "receita mensal" ao mesmo tempo.
 * Duas escutas separadas no mesmo evento não garantiriam essa ordem — as duas
 * agendam em paralelo.
 *
 * <p>Com o profile "rabbit" ativo os eventos vão para o broker em vez do
 * ApplicationEventPublisher e este listener fica mudo — os gatilhos manuais
 * ({@code POST /transactions/reconcile-internal}, {@code /family/reconcile-transfers},
 * {@code /transactions/duplicates/sweep} e {@code /recurrences/detect}) continuam
 * disponíveis.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatementHygieneListener {

    private final StatementHygieneService hygieneService;
    private final HygieneCadence cadence;

    @EventListener
    public void onStatementImported(StatementImportedEvent event) {
        HygieneWindow janela = HygieneWindow.of(event.getEarliestDate(), event.getLatestDate());
        // Importação duplicada/vazia não traz transação nova — nada a limpar.
        // Mas "zero linhas novas" COM datas é outra coisa: a sincronização só
        // carimbou a conta de origem em linhas antigas, e isso muda de que lado
        // elas ficam na varredura de duplicatas. Essa passa pela faxina
        if (event.getTransactionsImported() <= 0 && janela == null) return;
        UUID userId = event.getUserId();
        // O evento diz de que dia a que dia a importação mexeu: a faxina lê
        // só isso e o que está em volta. Evento sem datas (publicado por
        // código antigo, ou vindo de fora) cai na varredura total — e também
        // a primeira de cada intervalo, que é a repescagem (ver HygieneCadence)
        boolean total = janela == null || cadence.fullSweepDue(userId);
        Mono.fromCallable(() -> total
                        ? hygieneService.runFor(userId)
                        : hygieneService.runFor(userId, janela))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        resultado -> { },
                        error -> {
                            // sem isto, o que a faxina deixou pela metade só
                            // seria consertado quando o usuário pedisse
                            cadence.failed(userId);
                            log.warn("Faxina pós-importação falhou (best-effort): {}",
                                    error.getMessage());
                        },
                        // no término, e não no valor: a marca depende de a
                        // faxina ter chegado ao fim, não do que ela devolveu
                        () -> {
                            if (total) cadence.fullSweepDone(userId);
                            log.info("Faxina pós-importação concluída ({}), user={}",
                                    total ? "total" : "janela", userId);
                        });
    }
}
