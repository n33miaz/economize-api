package br.com.economize.controller;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.statement.BankTransactionResponse;
import br.com.economize.dto.statement.ReviewApplyRequest;
import br.com.economize.dto.statement.ReviewGroupResponse;
import br.com.economize.dto.statement.UpdateTransactionAliasRequest;
import br.com.economize.model.BankTransaction;
import br.com.economize.service.TransactionAliasService;
import br.com.economize.service.TransactionReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transações", description = "Listagem com filtros, apelido e fluxo de revisão de categorização")
public class TransactionController {

    private final TransactionReviewService reviewService;
    private final TransactionAliasService aliasService;

    @Operation(summary = "Listar transações bancárias",
            description = "Período opcional por `month=YYYY-MM` OU pelo par `start`/`end` em datas ISO "
                    + "`YYYY-MM-DD` inclusivas (mesma janela ancorada da análise — EC-092); sem período, "
                    + "devolve o histórico. Filtros adicionais: status de revisão e categoria. "
                    + "Mês e janela juntos, janela pela metade, `end` antes de `start` ou janela acima de "
                    + "366 dias respondem 400 (ProblemDetail). `description` já vem com o apelido quando "
                    + "existe; `originalDescription` traz sempre o descritivo do banco. "
                    + "`accountId` (EC-113) recorta por ORIGEM — é como se pede \"o que gastei NO CARTÃO "
                    + "neste mês\"; os ids saem de GET /api/v1/accounts. Cada linha devolve o `accountId` "
                    + "dela, nulo quando a origem não é conhecida (histórico e upload manual de arquivo).")
    @GetMapping
    public Mono<List<BankTransactionResponse>> list(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) BankTransaction.ReviewStatus status,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID accountId) {
        AnalysisWindow window = AnalysisWindow.resolve(month, start, end);
        return Mono.fromCallable(() -> reviewService
                        .listTransactions(email, window, status, categoryId, accountId).stream()
                        .map(BankTransactionResponse::from)
                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Renomear transação (apelido)",
            description = "Troca o rótulo de APRESENTAÇÃO da transação: `description` passa a devolver o "
                    + "apelido em toda listagem, enquanto `originalDescription` continua trazendo o "
                    + "descritivo do banco. `displayAlias` nulo ou em branco limpa o apelido. Máximo de 80 "
                    + "caracteres (400 com ProblemDetail acima disso). Transação inexistente OU de outro "
                    + "usuário responde 404 — o dono é filtro da consulta, não checagem posterior. "
                    + "O apelido não altera categorização, regras aprendidas, recorrência nem dedupe.")
    @PatchMapping("/{id}/alias")
    public Mono<BankTransactionResponse> updateAlias(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTransactionAliasRequest request) {
        return Mono.fromCallable(() -> aliasService.rename(email, id, request.displayAlias()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(BankTransactionResponse::from);
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
}
