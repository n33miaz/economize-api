package br.com.economize.controller;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.analytics.DebtOverviewResponse;
import br.com.economize.dto.analytics.MonthlyAnalyticsResponse;
import br.com.economize.service.AnalyticsService;
import br.com.economize.service.DebtInsightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Análise", description = "Consolidação mensal por categoria e comparação entre meses")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final DebtInsightService debtInsightService;

    @Operation(summary = "Consolidação de um período",
            description = "Entradas, saídas, quebra por categoria e delta vs período anterior. "
                    + "Aceita `month=YYYY-MM` (mês do calendário, comparado com o mês anterior) OU o par "
                    + "`start`/`end` em datas ISO `YYYY-MM-DD` inclusivas (janela ancorada, ex.: "
                    + "2026-07-12 a 2026-08-12, comparada com a janela imediatamente anterior de MESMO "
                    + "tamanho). Sem nenhum parâmetro: mês atual. Mês e janela juntos, janela pela metade, "
                    + "`end` antes de `start` ou janela acima de 366 dias respondem 400 (ProblemDetail). "
                    + "A data considerada é a de lançamento informada pelo extrato.")
    @GetMapping("/monthly")
    public Mono<MonthlyAnalyticsResponse> monthly(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        AnalysisWindow requested = AnalysisWindow.resolve(month, start, end);
        // sem parâmetro nenhum a tela continua abrindo no mês corrente, com a
        // mesma referência de fuso das janelas de agregação
        AnalysisWindow window = requested != null
                ? requested
                : AnalysisWindow.ofMonth(YearMonth.now(ZoneOffset.UTC));
        return Mono.fromCallable(() -> analyticsService.analyze(email, window))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Quanto do período é dívida",
            description = "Separa financiamento, parcelamento, consórcio, empréstimo e rotativo do "
                    + "consumo comum — sem isso o app soma a parcela do carro com o mercado e chama "
                    + "tudo de despesa. A classificação é derivada da descrição do extrato (ou do "
                    + "apelido, quando houver). Mesmos parâmetros de janela do /monthly.")
    @GetMapping("/debt")
    public Mono<DebtOverviewResponse> debt(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        AnalysisWindow requested = AnalysisWindow.resolve(month, start, end);
        AnalysisWindow window = requested != null
                ? requested
                : AnalysisWindow.ofMonth(YearMonth.now(ZoneOffset.UTC));
        return Mono.fromCallable(() -> debtInsightService.summarize(email, window))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Meses com movimentação", description = "Do mais recente ao mais antigo — alimenta o seletor de meses.")
    @GetMapping("/months")
    public Mono<List<String>> months(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> analyticsService.monthsWithData(email))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
