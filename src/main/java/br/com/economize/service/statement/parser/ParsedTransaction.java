package br.com.economize.service.statement.parser;

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

    // Perna de movimentação entre contas do próprio titular (EC-106): pagamento
    // de fatura de cartão, dos dois lados. Só os conectores sabem disso na
    // importação (é o tipo da conta de origem que denuncia); os parsers de
    // arquivo deixam no default false, e por isso o campo não é obrigatório em
    // nenhum builder já escrito.
    boolean internalTransfer;

    // Origem do lançamento (EC-113): id da ConnectorAccount que o conector já
    // resolveu antes de puxar as transações. Como internalTransfer, só os
    // conectores sabem disso — os parsers de arquivo deixam nulo, porque o
    // upload manual não tem conta de provedor, e por isso o campo não é
    // obrigatório em nenhum builder já escrito.
    java.util.UUID accountId;
}
