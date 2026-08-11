package br.com.economize.service.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class InMemoryDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher delegate;

    public InMemoryDomainEventPublisher(ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(DomainEvent event) {
        log.debug("[event] {} at {}", event.type(), event.occurredAt());
        delegate.publishEvent(event);
    }
}
