package br.com.painel_economico.service.statement.category;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedCategorizationServiceTest {

    private final RuleBasedCategorizationService service = new RuleBasedCategorizationService();

    @Test
    void mapsFoodKeywords() {
        assertThat(service.categorize("IFOOD ORDER 9821", "DEBIT")).isEqualTo(TransactionCategory.FOOD);
        assertThat(service.categorize("Supermercado Extra", "DEBIT")).isEqualTo(TransactionCategory.FOOD);
    }

    @Test
    void mapsTransport() {
        assertThat(service.categorize("UBER TRIP", "DEBIT")).isEqualTo(TransactionCategory.TRANSPORT);
        assertThat(service.categorize("POSTO IPIRANGA", "DEBIT")).isEqualTo(TransactionCategory.TRANSPORT);
    }

    @Test
    void defaultsCreditToIncome() {
        assertThat(service.categorize("Algo desconhecido", "CREDIT")).isEqualTo(TransactionCategory.INCOME);
    }

    @Test
    void fallsBackToOther() {
        assertThat(service.categorize("Compra qualquer obscura", "DEBIT")).isEqualTo(TransactionCategory.OTHER);
    }
}
