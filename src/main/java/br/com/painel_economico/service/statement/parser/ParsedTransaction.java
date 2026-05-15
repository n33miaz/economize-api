package br.com.painel_economico.service.statement.parser;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Value
@Builder
public class ParsedTransaction {
    String externalId;
    String type; // CREDIT, DEBIT
    BigDecimal amount;
    String description;
    OffsetDateTime date;
}
