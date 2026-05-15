package br.com.painel_economico.service.event;

import lombok.Value;

import java.util.UUID;

@Value
public class ReportGeneratedEvent implements DomainEvent {
    UUID userId;
    String period;
    UUID reportId;

    @Override
    public String type() {
        return "report.generated";
    }
}
