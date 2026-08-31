package br.com.economize.controller;

import br.com.economize.dto.wish.WishRequests;
import br.com.economize.dto.wish.WishResponses;
import br.com.economize.service.wish.IncomeSourceService;
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
 * De onde vem o dinheiro e quanto se trabalha por ele (EC-135 e EC-141).
 *
 * <p>Salário e vale-refeição têm calendários diferentes, e é isso que a fonte
 * guarda: a âncora de cada um. Sem essa distinção, a compra paga com o VR que
 * caiu dia 25 era cobrada do mês que estava fechando em vez do que ia abrir.
 */
@RestController
@RequestMapping("/api/v1/income")
@RequiredArgsConstructor
@Tag(name = "Renda e jornada", description = "Fontes de renda com calendário próprio (salário, VR, VA) e a jornada de trabalho que converte dinheiro em horas")
public class IncomeController {

    private final IncomeSourceService incomeSourceService;

    @Operation(summary = "Panorama de renda",
            description = "Fontes cadastradas, jornada declarada e as fontes que o motor de "
                    + "recorrência SUGERE a partir do extrato e que ainda esperam confirmação.")
    @GetMapping
    public Mono<WishResponses.IncomeOverview> overview(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> incomeSourceService.overview(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Cadastrar fonte de renda")
    @PostMapping("/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<WishResponses.IncomeSourceItem> create(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody WishRequests.CreateIncomeSource request) {
        return Mono.fromCallable(() -> incomeSourceService.create(email, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Confirmar uma fonte sugerida pelo extrato",
            description = "Aceita a sugestão do motor de recorrência. O corpo é opcional: sem ele, "
                    + "valem o tipo, o nome, o valor e a âncora que a série já conhece.")
    @PostMapping("/suggestions/{seriesId}/accept")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<WishResponses.IncomeSourceItem> accept(
            @AuthenticationPrincipal String email,
            @PathVariable UUID seriesId,
            @RequestBody(required = false) WishRequests.CreateIncomeSource request) {
        return Mono.fromCallable(() -> incomeSourceService.acceptSuggestion(email, seriesId, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Editar fonte de renda")
    @PatchMapping("/sources/{id}")
    public Mono<WishResponses.IncomeSourceItem> update(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id,
            @Valid @RequestBody WishRequests.UpdateIncomeSource request) {
        return Mono.fromCallable(() -> incomeSourceService.update(email, id, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Excluir fonte de renda")
    @DeleteMapping("/sources/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@AuthenticationPrincipal String email, @PathVariable UUID id) {
        return Mono.fromRunnable(() -> incomeSourceService.delete(email, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Operation(summary = "Declarar a jornada de trabalho",
            description = "Dias por semana e horas por dia — o divisor que transforma o salário em "
                    + "valor da hora. PUT porque a jornada é uma só por pessoa.")
    @PutMapping("/work-profile")
    public Mono<WishResponses.WorkProfileItem> saveWorkProfile(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody WishRequests.SaveWorkProfile request) {
        return Mono.fromCallable(() -> incomeSourceService.saveWorkProfile(email, request))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
