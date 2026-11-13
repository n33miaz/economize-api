package br.com.economize.controller;

import br.com.economize.config.OpenApiConfig;
import br.com.economize.dto.integration.IntegrationShoppingResponses;
import br.com.economize.service.integration.IntegrationAdviceService;
import br.com.economize.service.shopping.ShoppingHistoryExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * As leituras de compras para outro sistema do dono (V44).
 *
 * <p>Tudo aqui é {@code GET} e entra com a CHAVE de integração, nunca com o
 * JWT de sessão — a cadeia de segurança destas rotas é outra
 * ({@code IntegrationSecurityConfig}). O {@code email} que chega é o do dono
 * da chave: a chave de uma pessoa só alcança o que essa pessoa veria no app.
 */
@RestController
@RequestMapping("/api/v1/integration/shopping")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.INTEGRATION_KEY)
@Tag(name = "Integração — compras", description = "Leituras para outro sistema do dono, autenticadas pela chave de integração (Bearer eco_...): o conselho de melhor dia de compra e o histórico de compras para migração. O JWT de sessão não vale aqui, e a chave não vale em nenhuma outra rota")
public class IntegrationShoppingController {

    private final IntegrationAdviceService adviceService;
    private final ShoppingHistoryExportService historyService;

    @Operation(summary = "Melhor dia de compra, padrão de renda e quanto está livre",
            description = "`incomePattern` é exatamente a resposta de `/api/v1/analytics/income-pattern` "
                    + "(valores em reais). `free` é o que sobra do próximo salário, como "
                    + "`/api/v1/income/committed` calcula, em centavos; sem salário confirmado com dia de "
                    + "pagamento, `salaryKnown` é falso e `freeCents` vem nulo. `generatedAt` é a hora "
                    + "desta resposta, em UTC. Responde com `ETag` calculado sobre o conteúdo (sem "
                    + "`generatedAt`): mandando-o de volta em `If-None-Match`, a resposta é 304 sem corpo "
                    + "enquanto nada mudou.")
    @GetMapping("/advice")
    public Mono<ResponseEntity<IntegrationShoppingResponses.IntegrationAdvice>> integrationShoppingAdvice(
            @AuthenticationPrincipal String email,
            ServerWebExchange exchange) {
        return Mono.fromCallable(() -> adviceService.advice(email))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> {
                    // `private`: a resposta é da pessoa, nenhum proxy pode
                    // guardá-la; `no-cache`: quem guarda revalida sempre
                    CacheControl cache = CacheControl.noCache().cachePrivate();
                    if (exchange.checkNotModified(result.etag())) {
                        return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                                .eTag(result.etag()).cacheControl(cache).build();
                    }
                    return ResponseEntity.ok()
                            .eTag(result.etag()).cacheControl(cache).body(result.advice());
                });
    }

    @Operation(summary = "Histórico de compras, página a página",
            description = "As compras do dono da chave e as que a casa dele compartilhou (`own` diz qual é "
                    + "qual), da mais antiga para a mais nova, com os itens vivos — item removido não vem. "
                    + "`limit` vale 50 por padrão e 200 no máximo. Para a página seguinte, mande em `cursor` "
                    + "o `nextCursor` recebido; `hasMore` diz se há mais agora. O `nextCursor` vem também na "
                    + "última página: guardado, ele traz depois só as compras criadas desde então. Datas em "
                    + "UTC (ISO-8601) e dinheiro em centavos inteiros. `paidTotalCents` é a nota quando "
                    + "informada, senão a soma dos itens pegos. Cursor ilegível responde 400.")
    @GetMapping("/trips")
    public Mono<IntegrationShoppingResponses.IntegrationTripPage> integrationShoppingTrips(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return Mono.fromCallable(() -> historyService.page(email, cursor, limit))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
