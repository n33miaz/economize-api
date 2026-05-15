package br.com.painel_economico.service.event;

public interface DomainEventPublisher {
    void publish(DomainEvent event);
}
