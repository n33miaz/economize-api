package br.com.economize.controller;

import br.com.economize.model.SweepRun;
import br.com.economize.service.WatchmanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Os vigias e os recados deles — EC-202.
 *
 * <p>Seis varreduras rodam sozinhas depois de cada importação e mudam os
 * números do usuário. Este recurso é a prestação de contas delas: quem são,
 * o que fizeram na última passada, e como desfazer.
 */
@RestController
@RequestMapping("/api/v1/watchmen")
@RequiredArgsConstructor
@Tag(name = "Vigias",
        description = "As varreduras que trabalham sozinhas, com recado e desfazer (EC-202)")
public class WatchmanController {

    private final WatchmanService watchmanService;

    @Operation(summary = "Quem são os vigias",
            description = "Nome, uma linha de função e a frequência de cada um. A lista é FIXA e "
                    + "vem do código: ela existe para o usuário saber quem trabalha no extrato "
                    + "dele mesmo antes da primeira passada — um trabalhador que só aparece "
                    + "depois de mexer nos números já apareceu tarde.")
    @GetMapping
    public Mono<List<Watchman>> list() {
        return Mono.fromCallable(() -> Arrays.stream(SweepRun.Kind.values())
                        .map(kind -> new Watchman(kind, WatchmanService.nameOf(kind),
                                WatchmanService.roleOf(kind),
                                "a cada importação de extrato",
                                kind != SweepRun.Kind.RECURRENCE))
                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Os recados das últimas passadas",
            description = "As 50 passadas mais recentes, da mais nova para a mais velha. "
                    + "`affected=0` é registro legítimo: \"passei e não achei nada\" é resposta, e "
                    + "sem ela o silêncio é ambíguo. `canUndo` já vem calculado para a tela não "
                    + "desenhar um botão que não funciona — a detecção de recorrência não se "
                    + "desfaz por aqui, porque ela não marca linha nenhuma.")
    @GetMapping("/notes")
    public Mono<List<WatchmanService.Note>> notes(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> watchmanService.notesFor(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Desfazer uma passada",
            description = "Solta exatamente as linhas que AQUELA passada marcou — nunca a varredura "
                    + "inteira. Uma linha que o usuário marcou à mão depois continua marcada, "
                    + "porque decisão de gente vence varredura em qualquer direção. A passada "
                    + "continua no histórico marcada como desfeita: apagá-la esconderia que o "
                    + "vigia errou, que é o que o histórico existe para mostrar.")
    @PostMapping("/notes/{runId}/undo")
    public Mono<WatchmanService.Note> undo(
            @AuthenticationPrincipal String email,
            @PathVariable UUID runId) {
        return Mono.fromCallable(() -> watchmanService.undo(email, runId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * @param frequency  quando ele trabalha — o usuário precisa saber que
     *                   ninguém mexe no extrato dele fora disso
     * @param undoable   se as passadas dele podem ser desfeitas
     */
    public record Watchman(SweepRun.Kind kind, String name, String role,
                           String frequency, boolean undoable) {
    }
}
