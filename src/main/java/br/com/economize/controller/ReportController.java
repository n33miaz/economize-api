package br.com.economize.controller;

import br.com.economize.dto.report.CreateReportRequest;
import br.com.economize.dto.report.ReportResponse;
import br.com.economize.model.Report;
import br.com.economize.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(name = "Relatórios", description = "Relatórios consolidados de gastos por período")
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "Gerar novo relatório")
    @PostMapping
    public Mono<ResponseEntity<ReportResponse>> create(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody CreateReportRequest request) {
        return Mono.fromCallable(() -> reportService.generate(email, request.period(), request.startDate(), request.endDate()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(report -> ResponseEntity.ok(ReportResponse.from(report)));
    }

    @Operation(summary = "Listar relatórios do usuário")
    @GetMapping
    public Mono<Page<ReportResponse>> list(
            @AuthenticationPrincipal String email,
            @RequestParam(required = false) Report.Period period,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Mono.fromCallable(() -> reportService.list(email, period, page, size).map(ReportResponse::from))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Detalhe de relatório")
    @GetMapping("/{id}")
    public Mono<ReportResponse> detail(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id) {
        return Mono.fromCallable(() -> ReportResponse.from(reportService.detail(email, id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(summary = "Apagar relatório")
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(
            @AuthenticationPrincipal String email,
            @PathVariable UUID id) {
        return Mono.fromRunnable(() -> reportService.delete(email, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
