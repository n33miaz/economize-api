package br.com.economize.controller;

import br.com.economize.dto.wish.WishRequests;
import br.com.economize.dto.wish.WishResponses;
import br.com.economize.service.wish.WishService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

/**
 * Desejos (EC-140): o que a pessoa quer, medido no que ela gasta de vida para
 * conseguir.
 *
 * <p>Toda resposta traz a projeção junto do desejo. Separar em dois endpoints
 * faria a tela pedir duas vezes para mostrar um cartão — e o cálculo é barato,
 * porque o retrato financeiro é feito uma vez para a lista inteira.
 */
@RestController
@RequestMapping("/api/v1/wishes")
@RequiredArgsConstructor
@Tag(name = "Desejos", description = "Desejos e metas: quanto custam em horas de trabalho, em quantas parcelas cabem na sobra e o que mudaria a data")
public class WishController {

    private final WishService wishService;

    @Operation(summary = "Listar desejos com projeção",
            description = "Devolve o retrato financeiro (valor da hora, sobra típica) e cada desejo "
                    + "já projetado contra ele. O campo gaps diz o que falta para o cálculo ficar "
                    + "completo — jornada de trabalho, renda confirmada ou histórico de extrato.")
    @GetMapping
    public Mono<WishResponses.WishList> list(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> wishService.list(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Detalhar um desejo")
    @GetMapping("/{id}")
    public Mono<WishResponses.WishItem> get(@AuthenticationPrincipal String email,
                                            @PathVariable UUID id) {
        return Mono.fromCallable(() -> wishService.get(email, id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Cadastrar um desejo")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<WishResponses.WishItem> create(@AuthenticationPrincipal String email,
                                               @Valid @RequestBody WishRequests.CreateWish request) {
        return Mono.fromCallable(() -> wishService.create(email, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Editar um desejo",
            description = "Altera só os campos enviados. Mudar o status para GOAL é o que transforma "
                    + "o desejo em meta ativa.")
    @PatchMapping("/{id}")
    public Mono<WishResponses.WishItem> update(@AuthenticationPrincipal String email,
                                               @PathVariable UUID id,
                                               @Valid @RequestBody WishRequests.UpdateWish request) {
        return Mono.fromCallable(() -> wishService.update(email, id, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Confirmar que o desejo virou compra",
            description = "Fecha o ciclo desejo → meta → compra. O valor já guardado é preservado: "
                    + "é o histórico de quanto a pessoa tinha juntado no dia da compra.")
    @PostMapping("/{id}/purchase")
    public Mono<WishResponses.WishItem> purchase(@AuthenticationPrincipal String email,
                                                 @PathVariable UUID id,
                                                 @RequestBody(required = false) WishRequests.PurchaseWish request) {
        WishRequests.PurchaseWish body = request != null
                ? request : new WishRequests.PurchaseWish(null, null);
        return Mono.fromCallable(() -> wishService.purchase(email, id, body))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Excluir um desejo")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@AuthenticationPrincipal String email, @PathVariable UUID id) {
        return Mono.fromRunnable(() -> wishService.delete(email, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
