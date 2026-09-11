package br.com.economize.controller;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.analytics.DailyTotalResponse;
import br.com.economize.dto.analytics.DebtOverviewResponse;
import br.com.economize.dto.analytics.MonthlyAnalyticsResponse;
import br.com.economize.service.AnalyticsService;
import br.com.economize.service.CategoryBudgetService;
import br.com.economize.service.DebtInsightService;
import br.com.economize.service.InstallmentProjectionService;
import br.com.economize.service.SubscriptionHunterService;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Análise", description = "Consolidação mensal por categoria e comparação entre meses")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final CategoryBudgetService categoryBudgetService;
    private final DebtInsightService debtInsightService;
    private final InstallmentProjectionService installmentProjectionService;
    private final SubscriptionHunterService subscriptionHunterService;

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

    @Operation(summary = "Tetos por categoria",
            description = "EC-204: os limites que o usuário pôs. O limite é MENSAL mesmo quando ele "
                    + "lê o gasto por ciclo — a intenção (\"não passar de R$ 800 em mercado\") é "
                    + "mensal, e o recorte é escolha de tela.")
    @GetMapping("/budgets")
    public Mono<List<CategoryBudgetService.Budget>> budgets(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> categoryBudgetService.list(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Pôr ou mudar o teto de uma categoria",
            description = "Upsert: reajustar o limite muda o MESMO teto, não cria um segundo. "
                    + "Zero ou negativo responde 400 — não é teto. Categoria de outro dono responde "
                    + "404, e não 403, para não confirmar que ela existe.")
    @PutMapping("/budgets/{categoryId}")
    public Mono<CategoryBudgetService.Budget> setBudget(
            @AuthenticationPrincipal String email,
            @PathVariable UUID categoryId,
            @RequestParam java.math.BigDecimal monthlyLimit) {
        return Mono.fromCallable(() -> categoryBudgetService.set(email, categoryId, monthlyLimit))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Tirar o teto de uma categoria")
    @DeleteMapping("/budgets/{categoryId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public Mono<Void> clearBudget(
            @AuthenticationPrincipal String email,
            @PathVariable UUID categoryId) {
        return Mono.fromRunnable(() -> categoryBudgetService.clear(email, categoryId))
                .subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Operation(summary = "Como cada teto está indo no período",
            description = "Responde DUAS perguntas, porque são diferentes: `exceeded` diz que já "
                    + "estourou, e `abovePace` diz que o ritmo leva a estourar. \"Você está em 20% do "
                    + "limite\" é verdade e é inútil — 20% no terceiro dia é ruim e no vigésimo oitavo "
                    + "é ótimo. O teto mensal é esticado (ou encolhido) para o tamanho da janela, e o "
                    + "aviso respeita o piso de materialidade: estourar por R$ 0,50 não é notícia.")
    @GetMapping("/budgets/status")
    public Mono<CategoryBudgetService.Status> budgetStatus(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        AnalysisWindow window = AnalysisWindow.resolve(month, start, end);
        return Mono.fromCallable(() -> categoryBudgetService.statusFor(email, window,
                        java.time.LocalDate.now(ZoneOffset.UTC)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "O que você paga todo mês, e quanto é por ano",
            description = "EC-203: as assinaturas detectadas, com o total ANUAL — ninguém cancela uma "
                    + "de R$ 23,90; muita gente cancela uma de R$ 286,80 por ano. O valor está no "
                    + "FILTRO: medida contra o extrato real, a regra ingênua de \"mesmo valor em três "
                    + "meses\" achou 24 candidatas das quais 2 eram assinaturas — as outras eram "
                    + "aplicação de CDB, saque de valor redondo, Pix para pessoas e passagem de ônibus. "
                    + "Ficam de fora: receita, série descartada, cadência semanal ou irregular, valor "
                    + "variável (conta de luz não se cancela) e abaixo de R$ 5,00/mês. `silent=true` "
                    + "marca a que não cobra há mais de 45 dias.")
    @GetMapping("/subscriptions")
    public Mono<SubscriptionHunterService.Report> subscriptions(
            @AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> subscriptionHunterService.huntFor(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Parcelamentos em andamento",
            description = "EC-213/EC-217: quantas parcelas faltam de cada série e o total a vencer. "
                    + "A projeção é por MÊS, não por dia: parcela cai em ciclo de fatura, não em "
                    + "aniversário da compra — na série real medida, os intervalos foram de 25 e 36 "
                    + "dias, mas o mês avançou exatamente um por parcela. O valor a vencer usa a "
                    + "parcela mais RECENTE como estimador, porque a primeira carrega o arredondamento "
                    + "da divisão (R$ 199,98 contra R$ 199,96). Financiamento longo (acima de 36 "
                    + "parcelas) fica de fora: somá-lo ao parcelamento de cartão misturaria duas "
                    + "coisas que a pessoa pensa separado.")
    @GetMapping("/installments")
    public Mono<InstallmentProjectionService.Overview> installments(
            @AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> installmentProjectionService.overviewFor(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Totais por dia do período",
            description = "Alimenta o calendário de gastos (EC-235): quanto saiu e quanto entrou em "
                    + "cada dia, com as MESMAS exclusões de toda soma do app — movimentação entre "
                    + "contas do próprio titular, aplicação e resgate, par de estorno e duplicata "
                    + "descartada. Dia sem movimento não volta: quem monta a grade é a tela, que sabe "
                    + "quantos dias o mês tem. Aceita os mesmos parâmetros de /monthly.")
    @GetMapping("/daily")
    public Mono<List<DailyTotalResponse>> daily(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        AnalysisWindow window = AnalysisWindow.resolve(month, start, end);
        return Mono.fromCallable(() -> analyticsService.dailyTotals(email, window))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Meses com movimentação", description = "Do mais recente ao mais antigo — alimenta o seletor de meses.")
    @GetMapping("/months")
    public Mono<List<String>> months(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> analyticsService.monthsWithData(email))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
