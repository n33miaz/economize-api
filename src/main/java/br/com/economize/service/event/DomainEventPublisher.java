package br.com.economize.service.event;

public interface DomainEventPublisher {
    void publish(DomainEvent event);
}
