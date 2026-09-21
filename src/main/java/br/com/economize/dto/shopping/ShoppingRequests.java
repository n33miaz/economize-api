package br.com.economize.dto.shopping;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Os corpos de entrada do carrinho de compras (V39).
 *
 * <p>O {@code PUT} é o corpo de uma sincronização, e uma sincronização não
 * pode falhar por um campo que o aparelho não tinha. Por isso, no cabeçalho
 * da viagem, <b>nulo quer dizer "não mexa"</b>: o aparelho da esposa, que só
 * acrescentou um item, manda o item e nada mais — e o nome do mercado que o
 * marido escreveu fica como está. Os tetos existem pelo motivo de sempre: a
 * coluna é finita, e estourar nela seria um 500 dizendo "tente mais tarde"
 * sobre algo que nunca vai passar.
 */
public final class ShoppingRequests {

    private ShoppingRequests() {
    }

    /**
     * A viagem inteira, ou a parte dela que este aparelho sabe.
     *
     * @param clientId        opcional no corpo (o da rota manda); se vier, tem que ser o mesmo
     * @param status          OPEN, CLOSED ou RECONCILED — texto validado no serviço, para
     *                        responder 400 com os valores em vez de 500 na desserialização
     * @param shareWithFamily nulo mantém; só o DONO da viagem muda isto
     * @param items           cada item funde por {@code clientId}, e o mais novo vence
     */
    public record UpsertTrip(
            @Size(max = 64, message = "Identificador da compra deve ter no máximo 64 caracteres")
            String clientId,

            @Size(max = 120, message = "Nome do mercado deve ter no máximo 120 caracteres")
            String storeName,

            String status,

            @DecimalMin(value = "0.00", message = "Orçamento não pode ser negativo")
            @Digits(integer = 15, fraction = 4, message = "Orçamento fora da faixa aceita")
            BigDecimal budget,

            OffsetDateTime startedAt,

            OffsetDateTime closedAt,

            @DecimalMin(value = "0.00", message = "Total da nota não pode ser negativo")
            @Digits(integer = 15, fraction = 4, message = "Total da nota fora da faixa aceita")
            BigDecimal receiptTotal,

            @Size(max = 500, message = "Observações devem ter no máximo 500 caracteres")
            String notes,

            Boolean shareWithFamily,

            @Valid
            List<UpsertItem> items
    ) {
    }

    /**
     * Um item como o aparelho o tem.
     *
     * @param quantity        nulo vale 1; três casas porque balança pesa em gramas
     * @param checked         nulo vale "pegou"
     * @param deleted         a lápide: verdadeiro remove nos outros aparelhos também
     * @param clientUpdatedAt o relógio do aparelho na última edição; nulo vale "agora"
     */
    public record UpsertItem(
            @NotBlank(message = "Identificador do item é obrigatório")
            @Size(max = 64, message = "Identificador do item deve ter no máximo 64 caracteres")
            String clientId,

            @NotBlank(message = "Nome do item é obrigatório")
            @Size(max = 120, message = "Nome do item deve ter no máximo 120 caracteres")
            String name,

            @DecimalMin(value = "0.000", inclusive = false, message = "Quantidade deve ser positiva")
            @Digits(integer = 7, fraction = 3, message = "Quantidade fora da faixa aceita")
            BigDecimal quantity,

            @DecimalMin(value = "0.00", message = "Preço não pode ser negativo")
            @Digits(integer = 15, fraction = 4, message = "Preço fora da faixa aceita")
            BigDecimal unitPrice,

            @Size(max = 200, message = "Promoção deve ter no máximo 200 caracteres")
            String promoNote,

            Boolean checked,

            @Size(max = 200, message = "Referência da foto deve ter no máximo 200 caracteres")
            String photoRef,

            Boolean deleted,

            OffsetDateTime clientUpdatedAt
    ) {
    }

    /** Passou no caixa. O total da nota é opcional — pode vir depois, pela conciliação. */
    public record CloseTrip(
            @DecimalMin(value = "0.00", message = "Total da nota não pode ser negativo")
            @Digits(integer = 15, fraction = 4, message = "Total da nota fora da faixa aceita")
            BigDecimal receiptTotal
    ) {
    }

    /** O lançamento do extrato que pagou esta compra. */
    public record ReconcileTrip(
            @NotNull(message = "Informe o lançamento do extrato")
            UUID transactionId
    ) {
    }
}
