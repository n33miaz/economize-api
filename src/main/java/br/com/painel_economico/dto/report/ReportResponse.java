package br.com.painel_economico.dto.report;

import br.com.painel_economico.model.Report;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ReportResponse(
        UUID id,
        Report.Period period,
        OffsetDateTime startDate,
        OffsetDateTime endDate,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        String dominantCategory,
        String summary,
        String categoriesJson,
        OffsetDateTime createdAt) {

    public static ReportResponse from(Report report) {
        return new ReportResponse(
                report.getId(),
                report.getPeriod(),
                report.getStartDate(),
                report.getEndDate(),
                report.getTotalIncome(),
                report.getTotalExpense(),
                report.getDominantCategory(),
                report.getSummary(),
                report.getCategoriesJson(),
                report.getCreatedAt());
    }
}
