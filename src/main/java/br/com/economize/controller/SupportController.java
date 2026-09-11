package br.com.economize.controller;

import br.com.economize.model.SupportTicket;
import br.com.economize.service.SupportTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

/**
 * Falar com uma pessoa — EC-209.
 *
 * <p>O chamado vive no servidor, e não num chat que some quando o app fecha.
 * É isso que permite à pessoa fechar o app, trocar de celular ou entrar de
 * outro lugar e ainda encontrar o que escreveu — e provar que pediu ajuda.
 */
@RestController
@RequestMapping("/api/v1/support")
@RequiredArgsConstructor
@Tag(name = "Suporte", description = "Chamados com prazo e histórico que não some (EC-209)")
public class SupportController {

    private final SupportTicketService supportService;

    @Operation(summary = "Abrir um chamado",
            description = "A resposta traz o PRAZO em que prometemos responder, gravado no "
                    + "chamado — se a política mudar amanhã, este chamado mantém a promessa de "
                    + "hoje. O prazo conta dias ÚTEIS: um chamado aberto na sexta à noite com "
                    + "prazo de domingo é uma promessa desenhada para ser quebrada. Até 3 "
                    + "chamados em aberto por pessoa: não é regra de negócio, é rede contra o "
                    + "toque repetido de quem está ansioso.")
    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<SupportTicketService.Ticket> open(
            @AuthenticationPrincipal String email,
            @RequestBody OpenTicket request) {
        return Mono.fromCallable(() -> supportService.open(email, request.subject(),
                        request.message(), request.appVersion(), request.screen()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Meus chamados",
            description = "Do mais novo para o mais velho. É esta lista que prova que o chamado "
                    + "não sumiu quando o app fechou. `overdue=true` diz que o prazo passou sem "
                    + "resposta — a tela precisa poder dizer isso em vez de fingir que está tudo "
                    + "no rumo.")
    @GetMapping("/tickets")
    public Mono<List<SupportTicketService.Ticket>> list(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> supportService.listFor(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Encerrar um chamado",
            description = "Encerrar é da pessoa, não nosso: quem resolveu sozinho não deveria "
                    + "precisar esperar alguém fechar. O chamado continua no histórico — sumir "
                    + "seria perder a prova de que ela pediu ajuda.")
    @PostMapping("/tickets/{id}/close")
    public Mono<SupportTicketService.Ticket> close(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id) {
        return Mono.fromCallable(() -> supportService.close(email, id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * @param appVersion e {@code screen}: o que a pessoa estava vendo. Sem eles,
     *                   metade dos chamados começa com uma ida e volta só para
     *                   descobrir a tela — e cada ida e volta custa um dia
     */
    public record OpenTicket(SupportTicket.Subject subject, String message,
                             String appVersion, String screen) {
    }
}
