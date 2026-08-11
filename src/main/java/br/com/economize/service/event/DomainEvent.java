package br.com.economize.service.event;

import java.time.OffsetDateTime;

public interface DomainEvent {
    String type();

    default OffsetDateTime occurredAt() {
        return OffsetDateTime.now();
    }
}
