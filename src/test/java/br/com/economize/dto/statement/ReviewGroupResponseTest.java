package br.com.economize.dto.statement;

import br.com.economize.model.BankTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ReviewGroupResponseTest {

    @Test
    void groupsByNormalizedDescriptionAndCategorySummingAmountsWithBiggestGroupsFirst() {
        UUID foodId = UUID.randomUUID();
        UUID transportId = UUID.randomUUID();
        BankTransaction ifood1 = tx("ifood rest", foodId, "-30.00");
        BankTransaction ifood2 = tx("ifood rest", foodId, "-45.50");
        BankTransaction ifoodOtherSuggestion = tx("ifood rest", transportId, "-10.00");
        BankTransaction uber = tx("uber", transportId, "-20.00");

        // entrelaçadas de propósito: o agrupamento não pode depender da ordem de chegada
        List<ReviewGroupResponse> groups =
                ReviewGroupResponse.groupsFrom(List.of(ifood1, ifoodOtherSuggestion, ifood2, uber));

        assertThat(groups).hasSize(3);
        ReviewGroupResponse biggest = groups.get(0);
        assertThat(biggest.normalizedDescription()).isEqualTo("ifood rest");
        assertThat(biggest.suggestedCategoryId()).isEqualTo(foodId);
        assertThat(biggest.transactions()).hasSize(2);
        assertThat(biggest.totalAmount()).isEqualByComparingTo("-75.50");
        assertThat(groups.get(1).transactions()).hasSize(1);
        assertThat(groups.get(2).transactions()).hasSize(1);

        assertThat(groups)
                .extracting(ReviewGroupResponse::normalizedDescription, ReviewGroupResponse::suggestedCategoryId)
                .containsExactlyInAnyOrder(
                        tuple("ifood rest", foodId),
                        tuple("ifood rest", transportId),
                        tuple("uber", transportId));
    }

    @Test
    void aliasShowsUpInTheSampleWithoutSplittingTheGroup() {
        UUID foodId = UUID.randomUUID();
        BankTransaction renamed = tx("ifood rest", foodId, "-30.00");
        renamed.setDisplayAlias("Janta da sexta");
        BankTransaction plain = tx("ifood rest", foodId, "-45.50");

        List<ReviewGroupResponse> groups = ReviewGroupResponse.groupsFrom(List.of(renamed, plain));

        // uma decisão só: o agrupamento é pela chave do motor, que o apelido não toca
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).normalizedDescription()).isEqualTo("ifood rest");
        // mas a amostra que o usuário lê já vem renomeada — é onde ele reconhece o gasto
        assertThat(groups.get(0).sampleDescription()).isEqualTo("Janta da sexta");
        assertThat(groups.get(0).transactions())
                .extracting(BankTransactionResponse::description, BankTransactionResponse::originalDescription)
                .containsExactly(
                        tuple("Janta da sexta", "IFOOD REST"),
                        tuple("IFOOD REST", "IFOOD REST"));
    }

    private BankTransaction tx(String normalized, UUID categoryId, String amount) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .transactionId(UUID.randomUUID().toString())
                .type("DEBIT")
                .amount(new BigDecimal(amount))
                .description(normalized.toUpperCase())
                .normalizedDescription(normalized)
                .categoryId(categoryId)
                .reviewStatus(BankTransaction.ReviewStatus.SUGGESTED)
                .categorizedBy(BankTransaction.CategorizedBy.KEYWORD)
                .confidence(new BigDecimal("0.70"))
                .date(OffsetDateTime.now())
                .build();
    }
}
