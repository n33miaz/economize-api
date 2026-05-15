package br.com.painel_economico.dto.report;

import br.com.painel_economico.model.Report;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public record CreateReportRequest(
        @NotNull Report.Period period,
        @NotNull OffsetDateTime startDate,
        @NotNull OffsetDateTime endDate) {}
