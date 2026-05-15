package br.com.painel_economico.service.event;

import br.com.painel_economico.service.statement.parser.StatementFormat;
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
