package br.com.economize.service.event;

import br.com.economize.service.statement.parser.StatementFormat;
import lombok.Value;

import java.util.UUID;

@Value
public class StatementImportedEvent implements DomainEvent {
    UUID userId;
    StatementFormat format;
    int transactionsImported;

    @Override
    public String type() {
        return "statement.imported";
    }
}
