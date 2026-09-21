package br.com.economize.controller;

import br.com.economize.dto.shopping.ShoppingRequests;
import br.com.economize.dto.shopping.ShoppingResponses;
import br.com.economize.service.shopping.ShoppingTripService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Carrinho de compras (V39): a compra registrada no corredor, offline,
 * sincronizada quando a rede volta e compartilhada com a casa.
 *
 * <p>A rota de escrita principal é um {@code PUT} por id do aparelho, e não
 * um {@code POST}: a viagem já existe no aparelho antes de existir aqui, e o
 * reenvio tem que ser inofensivo. Todas as leituras devolvem o que é visível
 * para o token — as viagens da pessoa e as que a casa dela compartilhou.
 */
@RestController
@RequestMapping("/api/v1/shopping")
@RequiredArgsConstructor
@Tag(name = "Carrinho de compras", description = "A compra registrada no mercado, offline: itens com preço e quantidade, total no corredor, fusão entre aparelhos da casa, conciliação com o extrato e histórico de preços")
public class ShoppingController {

    private final ShoppingTripService service;

    @Operation(summary = "Minhas compras e as da casa",
            description = "Da mais recente para a mais antiga, com os itens — inclusive os removidos "
                    + "(`deleted = true`), para o aparelho que ainda não soube da remoção. `status` "
                    + "filtra por OPEN, CLOSED ou RECONCILED; `limit` vale 50 por padrão e 200 no máximo. "
                    + "`total` é a soma de quantidade × preço dos itens pegos, calculada aqui.")
    @GetMapping("/trips")
    public Mono<List<ShoppingResponses.TripItem>> list(@AuthenticationPrincipal String email,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) Integer limit) {
        return Mono.fromCallable(() -> service.list(email, status, limit))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Enviar a compra (cria ou funde)",
            description = "Idempotente pelo id gerado no aparelho: reenviar não duplica. No cabeçalho, "
                    + "campo nulo mantém o que está gravado. Cada item funde pelo seu `clientId`: o "
                    + "mais novo por `clientUpdatedAt` vence, e `deleted = true` remove nos outros "
                    + "aparelhos também. `shareWithFamily` só vale para o dono; sem casa responde 400. "
                    + "RECONCILED não se alcança por aqui — é pela conciliação. Devolve a viagem "
                    + "consolidada com os ids do servidor. Compra de outra pessoa que a casa não "
                    + "compartilhou não existe para o token: cria-se uma nova com o mesmo id.")
    @PutMapping("/trips/{clientId}")
    public Mono<ShoppingResponses.TripItem> upsert(@AuthenticationPrincipal String email,
                                                   @PathVariable String clientId,
                                                   @Valid @RequestBody ShoppingRequests.UpsertTrip request) {
        return Mono.fromCallable(() -> service.upsert(email, clientId, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Passei no caixa",
            description = "Status CLOSED, com a hora do caixa se ainda não havia. `receiptTotal` é o "
                    + "total da nota, quando a pessoa já o tem; pode vir depois. Compra já conciliada "
                    + "responde 400.")
    @PostMapping("/trips/{clientId}/close")
    public Mono<ShoppingResponses.TripItem> close(@AuthenticationPrincipal String email,
                                                  @PathVariable String clientId,
                                                  @RequestBody(required = false)
                                                  @Valid ShoppingRequests.CloseTrip request) {
        return Mono.fromCallable(() -> service.close(email, clientId, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Conciliar com o extrato",
            description = "Amarra a compra ao lançamento que a pagou e muda o status para RECONCILED. O "
                    + "lançamento tem que ser um DÉBITO do próprio token — lançamento de outra pessoa "
                    + "responde 404. Sem total da nota, o valor do lançamento passa a ser o total.")
    @PostMapping("/trips/{clientId}/reconcile")
    public Mono<ShoppingResponses.TripItem> reconcile(@AuthenticationPrincipal String email,
                                                      @PathVariable String clientId,
                                                      @Valid @RequestBody ShoppingRequests.ReconcileTrip request) {
        return Mono.fromCallable(() -> service.reconcile(email, clientId, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Quem pode ter pago esta compra",
            description = "Débitos do próprio token entre um dia antes do início e cinco dias depois do "
                    + "caixa, com valor entre 80% e 120% da referência (a nota, ou a soma dos itens), do "
                    + "mais próximo em valor para o mais distante, no máximo 10. Sem preço anotado não há "
                    + "referência, e a lista volta vazia.")
    @GetMapping("/trips/{clientId}/reconcile-candidates")
    public Mono<List<ShoppingResponses.ReconcileCandidate>> reconcileCandidates(
            @AuthenticationPrincipal String email,
            @PathVariable String clientId) {
        return Mono.fromCallable(() -> service.reconcileCandidates(email, clientId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Quanto isto custou das outras vezes",
            description = "As últimas ocorrências do produto (nome comparado sem acento, minúsculo e "
                    + "com espaços colapsados) nas compras visíveis ao token, com o mercado, o preço e a "
                    + "data — e um resumo delas: último preço, menor, maior e média. `store` restringe "
                    + "a um mercado; `limit` vale 20 por padrão e 100 no máximo. Sem ocorrência, "
                    + "`summary` é nulo.")
    @GetMapping("/price-history")
    public Mono<ShoppingResponses.PriceHistory> priceHistory(@AuthenticationPrincipal String email,
                                                             @RequestParam String name,
                                                             @RequestParam(required = false) String store,
                                                             @RequestParam(required = false) Integer limit) {
        return Mono.fromCallable(() -> service.priceHistory(email, name, store, limit))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Resumo para a Home",
            description = "Quantas compras estão abertas, a última encerrada (com o total: a nota quando "
                    + "informada, senão a soma dos itens) e quanto saiu no mercado este mês.")
    @GetMapping("/summary")
    public Mono<ShoppingResponses.ShoppingSummary> summary(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> service.summary(email))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
