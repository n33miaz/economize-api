package br.com.economize.controller;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.recurrence.CreateRecurringSeriesRequest;
import br.com.economize.dto.recurrence.DetectionSummaryResponse;
import br.com.economize.dto.recurrence.ForecastResponse;
import br.com.economize.dto.recurrence.RecurringSeriesResponse;
import br.com.economize.dto.recurrence.UpdateRecurringSeriesRequest;
import br.com.economize.service.recurrence.RecurrenceDetectionService;
import br.com.economize.service.recurrence.RecurrenceForecastService;
import br.com.economize.service.recurrence.RecurringSeriesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/recurrences")
@RequiredArgsConstructor
@Tag(name = "Recorrências", description = "Séries recorrentes detectadas nos extratos ou agendadas pelo usuário: assinaturas, contas de consumo, renda, transferências internas e previsão de saldo")
public class RecurrenceController {

    private final RecurrenceDetectionService detectionService;
    private final RecurringSeriesService seriesService;
    private final RecurrenceForecastService forecastService;

    @Operation(summary = "Detectar recorrências nas transações do usuário",
            description = "Varre o histórico, agrupa por entidade e cria/atualiza séries e vínculos. "
                    + "Idempotente: re-executar sobre os mesmos dados não duplica nada.")
    @PostMapping("/detect")
    public Mono<DetectionSummaryResponse> detect(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> detectionService.detect(email))
                .subscribeOn(Schedulers.boundedElastic())
                .map(DetectionSummaryResponse::from);
    }

    @Operation(summary = "Listar séries recorrentes",
            description = "Inclui o próximo vencimento estimado. Séries INTERNAL (transferências do "
                    + "próprio titular) só aparecem com ?flow=INTERNAL. Por padrão só as ativas não "
                    + "descartadas; ?active=false lista as inativas (inclusive descartadas), que podem "
                    + "ser reativadas via PATCH active=true.")
    @GetMapping
    public Mono<List<RecurringSeriesResponse>> list(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) String flow,
            @RequestParam(required = false) Boolean active) {
        return Mono.fromCallable(() -> seriesService.list(email, flow, active))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Agendar série recorrente manual",
            description = "Cria uma série source=USER para gasto fixo/renda que o extrato ainda não "
                    + "provou. A chave de conciliação deriva do matchHint (ou do displayName) pelo "
                    + "mesmo extrator da detecção — transações reais futuras são vinculadas "
                    + "automaticamente pela varredura. Colisão com série existente do mesmo "
                    + "(usuário, chave, fluxo) responde 409.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<RecurringSeriesResponse> create(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody CreateRecurringSeriesRequest request) {
        return Mono.fromCallable(() -> seriesService.create(email, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Previsão de saldo período a período",
            description = "Projeta as séries ativas (EXPENSE/INCOME; INTERNAL e IRREGULAR ficam de "
                    + "fora) do período corrente em diante. No período corrente, ocorrências já "
                    + "conciliadas aparecem settled=true e não somam de novo. cumulativeNet parte de "
                    + "?startingBalance= quando informado (o sistema não tem saldo consolidado); "
                    + "sem ele, de zero. months é a quantidade de PERÍODOS, entre 1 e 12 (default 3).\n\n"
                    + "O recorte usa a mesma gramática de `/analytics/monthly` (EC-092): `month=YYYY-MM` "
                    + "(mês do calendário) OU o par `start`/`end` em datas ISO `YYYY-MM-DD` INCLUSIVAS, "
                    + "que descreve o PRIMEIRO período — tipicamente o ciclo ancorado que a tela já "
                    + "mostra (2026-07-12 a 2026-08-11). Os períodos seguintes são os ciclos contíguos "
                    + "que o continuam: cada um abre no dia seguinte ao fechamento do anterior e fecha "
                    + "na véspera da âncora do mês seguinte (12/08 → 11/09). Sem nenhum dos três, os "
                    + "períodos continuam sendo os meses do calendário a partir do mês corrente, "
                    + "idênticos aos de antes.\n\n"
                    + "Todo período traz `start`/`end` inclusivos e `month` (o mês em que ele COMEÇA); "
                    + "a resposta ecoa em `anchorDay` a âncora lida da janela. Mês e janela juntos, "
                    + "janela pela metade, `end` antes de `start`, janela acima de 366 dias ou data "
                    + "malformada respondem 400 (ProblemDetail). A data considerada é a de lançamento, "
                    + "em UTC.")
    @GetMapping("/forecast")
    public Mono<ForecastResponse> forecast(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) Integer months,
            @RequestParam(required = false) BigDecimal startingBalance,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        // resolvido AQUI, como em /analytics/monthly: a janela torta tem que
        // virar 400 antes de qualquer consulta, e é o mesmo parser para as duas
        // telas não aprenderem dialetos diferentes da mesma gramática. Null
        // (nenhum parâmetro) é o default do endpoint, que só o serviço conhece —
        // ele depende do "hoje" em UTC.
        AnalysisWindow window = AnalysisWindow.resolve(month, start, end);
        return Mono.fromCallable(() -> forecastService.forecast(email, months, startingBalance, window))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Editar série recorrente (parcial)",
            description = "Aceita nome, categoria, active (reativar desfaz o descarte), amountType, "
                    + "expectedAmount, cadence, anchorDay e a vigência startsAt/endsAt. Editar "
                    + "valor/tipo/ritmo promove a série a source=USER para a varredura não recalcular "
                    + "por cima da curadoria. anchorDay não se aplica a WEEKLY e endsAt não pode "
                    + "anteceder startsAt.")
    @PatchMapping("/{id}")
    public Mono<RecurringSeriesResponse> update(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRecurringSeriesRequest request) {
        return Mono.fromCallable(() -> seriesService.update(email, id, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Excluir série recorrente",
            description = "Criada pelo usuário é apagada de verdade; detectada vira inativa, "
                    + "preservando o histórico de vínculos.")
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> delete(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id) {
        return Mono.fromCallable(() -> seriesService.delete(email, id))
                .subscribeOn(Schedulers.boundedElastic())
                .map(deleted -> ResponseEntity.ok(Map.of(
                        "deleted", deleted,
                        "deactivated", !deleted)));
    }
}
