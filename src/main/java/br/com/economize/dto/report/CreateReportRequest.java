package br.com.economize.dto.report;

import br.com.economize.model.Report;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public record CreateReportRequest(
        @NotNull Report.Period period,
        @NotNull OffsetDateTime startDate,
        @NotNull OffsetDateTime endDate) {}
