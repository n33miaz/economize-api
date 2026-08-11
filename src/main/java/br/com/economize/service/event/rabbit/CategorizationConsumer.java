package br.com.economize.service.event.rabbit;

import br.com.economize.service.event.StatementImportedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Profile("rabbit")
@Component
public class CategorizationConsumer {

    @RabbitListener(queues = RabbitConfig.CATEGORIZE_QUEUE)
    public void onStatementImported(StatementImportedEvent event) {
        log.info("[async-categorize] received statement.imported user={} count={}",
                event.getUserId(), event.getTransactionsImported());
    }
}
