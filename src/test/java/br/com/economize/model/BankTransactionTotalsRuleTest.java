package br.com.economize.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BankTransaction — o que entra numa soma")
class BankTransactionTotalsRuleTest {

    private static BankTransaction.BankTransactionBuilder base() {
        return BankTransaction.builder().amount(new BigDecimal("-100.00"));
    }

    @Test
    @DisplayName("Linha comum e Pix do casal contam na visão pessoal")
    void contam() {
        assertThat(base().build().countsInTotals()).isTrue();
        assertThat(base().familyTransfer(true).build().countsInTotals()).isTrue();
    }

    @Test
    @DisplayName("Transferência interna, ignorada e estornada ficam fora")
    void naoContam() {
        assertThat(base().internalTransfer(true).build().countsInTotals()).isFalse();
        assertThat(base().ignored(true).build().countsInTotals()).isFalse();
        assertThat(base().refunded(true).build().countsInTotals()).isFalse();
    }
}
