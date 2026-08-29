package br.com.economize.service;

import br.com.economize.model.BankTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A dedupe de importação continua cega ao apelido (EC-094): o ledger de
 * reconciliação é construído a partir da descrição NORMALIZADA da transação já
 * gravada, e é ela que casa com a linha que chega no arquivo. Se o apelido
 * entrasse nessa chave, renomear uma transação faria a próxima importação do
 * mesmo extrato reimportá-la como se fosse nova.
 */
class BankStatementLedgerAliasTest {

    private static final OffsetDateTime DAY =
            OffsetDateTime.of(LocalDate.of(2026, 7, 12), LocalTime.NOON, ZoneOffset.UTC);
    private static final BigDecimal AMOUNT = new BigDecimal("-99.90");

    @Test
    void ledgerMatchesTheBankNormalizedKeyEvenWhenTheTransactionWasRenamed() {
        BankStatementService.ReconciliationLedger ledger =
                new BankStatementService.ReconciliationLedger(List.of(renamedTransaction()));

        assertThat(ledger.consumeExact(DAY, AMOUNT, "fitmax")).isTrue();
    }

    @Test
    void ledgerNeverMatchesByTheAlias() {
        BankStatementService.ReconciliationLedger ledger =
                new BankStatementService.ReconciliationLedger(List.of(renamedTransaction()));

        assertThat(ledger.consumeExact(DAY, AMOUNT, "academia")).isFalse();
        // a rede de dia+valor continua valendo (é o casamento entre formatos),
        // mas o crédito exato nunca foi consumido pelo apelido
        assertThat(ledger.consumeAny(DAY, AMOUNT)).isTrue();
    }

    private BankTransaction renamedTransaction() {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .transactionId(UUID.randomUUID().toString())
                .type("DEBIT")
                .amount(AMOUNT)
                .description("PAG*FITMAX 4321 SAO PAULO BRA")
                .normalizedDescription("fitmax")
                .displayAlias("Academia")
                .date(DAY)
                .build();
    }
}
