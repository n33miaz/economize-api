package br.com.economize.controller;

import br.com.economize.dto.statement.BankTransactionResponse;
import br.com.economize.dto.statement.ReviewApplyRequest;
import br.com.economize.dto.statement.ReviewGroupResponse;
import br.com.economize.model.BankTransaction;
import br.com.economize.service.TransactionReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transações", description = "Listagem com filtros e fluxo de revisão de categorização")
public class TransactionController {

    private final TransactionReviewService reviewService;

    @Operation(summary = "Listar transações bancárias",
            description = "Filtros opcionais: month=YYYY-MM, status de revisão e categoria.")
    @GetMapping
    public Mono<List<BankTransactionResponse>> list(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) BankTransaction.ReviewStatus status,
            @RequestParam(required = false) UUID categoryId) {
        YearMonth parsed = parseMonth(month);
        return Mono.fromCallable(() -> reviewService.listTransactions(email, parsed, status, categoryId).stream()
                        .map(BankTransactionResponse::from)
                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Fila de revisão agrupada",
            description = "Transações aguardando aprovação (sugeridas) ou ajuda (sem categoria), "
                    + "agrupadas por estabelecimento normalizado. uploadId restringe a uma importação.")
    @GetMapping("/review")
    public Mono<List<ReviewGroupResponse>> reviewQueue(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) UUID uploadId) {
        return Mono.fromCallable(() -> ReviewGroupResponse.groupsFrom(reviewService.reviewQueue(email, uploadId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Aplicar decisões de revisão em lote",
            description = "Confirma categorias por grupo de transações; por padrão aprende o padrão para as próximas importações.")
    @PatchMapping("/review")
    public Mono<Map<String, Object>> applyReview(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody ReviewApplyRequest request) {
        return Mono.fromCallable(() -> reviewService.apply(email, request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(outcome -> Map.of(
                        "confirmed", outcome.confirmed(),
                        "rulesSaved", outcome.rulesSaved()));
    }

    @Operation(summary = "Aprovar todas as sugestões pendentes",
            description = "Confirma tudo que o motor sugeriu (não toca as sem categoria). uploadId restringe a uma importação.")
    @PostMapping("/review/confirm-all")
    public Mono<Map<String, Object>> confirmAll(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) UUID uploadId) {
        return Mono.fromCallable(() -> reviewService.confirmAll(email, uploadId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(outcome -> Map.of(
                        "confirmed", outcome.confirmed(),
                        "rulesSaved", outcome.rulesSaved()));
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) return null;
        try {
            return YearMonth.parse(month);
        } catch (Exception e) {
            throw new IllegalArgumentException("Mês inválido — use o formato YYYY-MM");
        }
    }
}
