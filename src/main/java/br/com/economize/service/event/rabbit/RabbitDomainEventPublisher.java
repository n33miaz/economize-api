package br.com.economize.service.event.rabbit;

import br.com.economize.service.event.DomainEvent;
import br.com.economize.service.event.DomainEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Profile("rabbit")
@Primary
@Component
public class RabbitDomainEventPublisher implements DomainEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public RabbitDomainEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(DomainEvent event) {
        log.debug("[rabbit-publish] {}", event.type());
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, event.type(), event);
    }
}
