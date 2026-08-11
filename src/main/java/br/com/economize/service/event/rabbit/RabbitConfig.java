package br.com.economize.service.event.rabbit;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("rabbit")
public class RabbitConfig {

    public static final String EXCHANGE = "economize.events";
    public static final String CATEGORIZE_QUEUE = "economize.categorize";

    @Bean
    public TopicExchange exchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue categorizeQueue() {
        return new Queue(CATEGORIZE_QUEUE, true);
    }

    @Bean
    public Binding categorizeBinding(Queue categorizeQueue, TopicExchange exchange) {
        return BindingBuilder.bind(categorizeQueue).to(exchange).with("statement.imported");
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
