package br.com.economize.dto.analytics;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * O corpo de {@code PUT /analytics/income-pattern/preference}.
 *
 * <p>Enums chegam como texto e são validados no service — enum no record
 * derrubaria a desserialização inteira com 500 em vez de responder 400 dizendo
 * qual valor era esperado (a mesma regra de {@code WishRequests}).
 *
 * @param cadence          MONTHLY ou WEEKLY — obrigatório, é a preferência em si
 * @param weekendPreferred nulo vale como verdadeiro: é o padrão da tabela
 * @param paymentMode      CASH ou CARD; nulo deixa o app decidir
 * @param cardAccountId    obrigatório quando {@code paymentMode} é CARD
 * @param fundingKind      SALARY, MEAL_VOUCHER ou FOOD_VOUCHER; nulo = o app escolhe
 */
public record PurchasePreferenceRequest(
        @NotBlank(message = "Informe a cadência: MONTHLY ou WEEKLY")
        String cadence,
        Boolean weekendPreferred,
        String paymentMode,
        UUID cardAccountId,
        String fundingKind
) {
}
