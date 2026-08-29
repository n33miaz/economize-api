package br.com.economize.dto.recurrence;

import br.com.economize.service.recurrence.RecurrenceDetectionService;

public record DetectionSummaryResponse(int seriesCreated, int seriesUpdated, int linksCreated) {

    public static DetectionSummaryResponse from(RecurrenceDetectionService.DetectionSummary summary) {
        return new DetectionSummaryResponse(
                summary.seriesCreated(), summary.seriesUpdated(), summary.linksCreated());
    }
}
