package br.com.economize.dto.recurrence;

import br.com.economize.model.RecurringSeries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RecurringSeriesResponse(
        UUID id,
        String merchantKey,
        String displayName,
        UUID categoryId,
        RecurringSeries.Flow flow,
        RecurringSeries.Cadence cadence,
        Integer anchorDay,
        Integer dayTolerance,
        RecurringSeries.AmountType amountType,
        BigDecimal expectedAmount,
        int occurrences,
        OffsetDateTime firstSeenAt,
        OffsetDateTime lastSeenAt,
        boolean active,
        // distingue, na listagem de inativas, o descarte do usuário da pausa por
        // staleness — só o segundo volta sozinho quando chega cobrança nova
        boolean dismissed,
        RecurringSeries.Source source,
        // vigência do agendamento manual; NULL nas séries detectadas
        LocalDate startsAt,
        LocalDate endsAt,
        LocalDate nextDueDate
) {
    public static RecurringSeriesResponse from(RecurringSeries series, LocalDate nextDueDate) {
        return new RecurringSeriesResponse(
                series.getId(),
                series.getMerchantKey(),
                series.getDisplayName(),
                series.getCategoryId(),
                series.getFlow(),
                series.getCadence(),
                series.getAnchorDay() != null ? series.getAnchorDay().intValue() : null,
                series.getDayTolerance() != null ? series.getDayTolerance().intValue() : null,
                series.getAmountType(),
                series.getExpectedAmount(),
                series.getOccurrences(),
                series.getFirstSeenAt(),
                series.getLastSeenAt(),
                series.isActive(),
                series.isDismissed(),
                series.getSource(),
                series.getStartsAt(),
                series.getEndsAt(),
                nextDueDate);
    }
}
