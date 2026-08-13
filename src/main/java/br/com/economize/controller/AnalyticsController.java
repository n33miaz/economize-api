package br.com.economize.controller;

import br.com.economize.dto.analytics.MonthlyAnalyticsResponse;
import br.com.economize.service.AnalyticsService;
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

    @Operation(summary = "Consolidação de um mês",
            description = "Entradas, saídas, quebra por categoria e delta vs mês anterior. Default: mês atual.")
    @GetMapping("/monthly")
    public Mono<MonthlyAnalyticsResponse> monthly(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) String month) {
        YearMonth parsed = parseMonth(month);
        return Mono.fromCallable(() -> analyticsService.monthly(email, parsed))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Meses com movimentação", description = "Do mais recente ao mais antigo — alimenta o seletor de meses.")
    @GetMapping("/months")
    public Mono<List<String>> months(@AuthenticationPrincipal String email) {
        return Mono.fromCallable(() -> analyticsService.monthsWithData(email))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            // mesma referência de fuso das janelas de agregação
            return YearMonth.now(ZoneOffset.UTC);
        }
        try {
            return YearMonth.parse(month);
        } catch (Exception e) {
            throw new IllegalArgumentException("Mês inválido — use o formato YYYY-MM");
        }
    }
}
