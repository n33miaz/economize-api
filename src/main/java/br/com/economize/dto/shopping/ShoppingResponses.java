package br.com.economize.dto.shopping;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * As respostas do carrinho de compras (V39).
 *
 * <p>O {@code total} é calculado no servidor, sempre do mesmo jeito (soma de
 * quantidade × preço dos itens que a pessoa pegou e não removeu), para que
 * dois aparelhos da mesma casa mostrem o mesmo número. As lápides
 * ({@code deleted = true}) VOLTAM na lista de itens: é assim que o aparelho
 * que ainda não soube da remoção fica sabendo.
 */
public final class ShoppingResponses {

    private ShoppingResponses() {
    }

    /**
     * @param ownerName        quem abriu a viagem — na casa, a tela mostra "compra do João"
     * @param sharedWithFamily a casa vê e edita (nome do campo é o do contrato — o mesmo que o
     *                         corpo do PUT recebe)
     * @param total            soma dos itens pegos, com preço, não removidos
     * @param itemCount        itens vivos (sem lápide), pegos ou não
     * @param updatedAt        o relógio do SERVIDOR; é o que o app guarda para saber se está em dia
     */
    public record TripItem(
            UUID id,
            String clientId,
            UUID ownerId,
            String ownerName,
            UUID familyGroupId,
            boolean sharedWithFamily,
            String storeName,
            String status,
            BigDecimal budget,
            OffsetDateTime startedAt,
            OffsetDateTime closedAt,
            BigDecimal receiptTotal,
            /** A chave de 44 dígitos do cupom, quando a nota foi lida. */
            String receiptKey,
            /** O CNPJ de quem emitiu, extraído da chave. */
            String receiptIssuerCnpj,
            UUID reconciledTransactionId,
            String notes,
            BigDecimal total,
            int itemCount,
            List<ItemResponse> items,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    /**
     * @param lineTotal   quantidade × preço; nulo enquanto não há preço
     * @param addedByName quem colocou no carrinho — "quem pegou isso?"
     */
    public record ItemResponse(
            UUID id,
            String clientId,
            String name,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            String promoNote,
            boolean checked,
            String photoRef,
            UUID addedBy,
            String addedByName,
            boolean deleted,
            OffsetDateTime clientUpdatedAt,
            OffsetDateTime updatedAt
    ) {
    }

    /**
     * Um lançamento do extrato que pode ter pago a compra.
     *
     * @param id         o {@code transactionId} que a rota de conciliação espera de volta — o
     *                   nome do campo é o do contrato
     * @param accountId  a conta/cartão de origem do lançamento, quando conhecida
     * @param amount     sempre positivo — o valor que saiu
     * @param difference {@code amount − referência} (a nota, ou a soma dos itens); zero é o casamento exato
     */
    public record ReconcileCandidate(
            UUID id,
            OffsetDateTime date,
            String description,
            BigDecimal amount,
            UUID accountId,
            BigDecimal difference
    ) {
    }

    /**
     * "Quanto isto custou das outras vezes."
     *
     * @param history a lista de ocorrências — nome do campo é o do contrato
     * @param summary nulo quando não há ocorrência nenhuma — nulo é "não sei", nunca zero
     */
    public record PriceHistory(
            String name,
            String normalizedName,
            PriceSummary summary,
            List<PriceOccurrence> history
    ) {
    }

    public record PriceOccurrence(
            String tripClientId,
            String storeName,
            BigDecimal unitPrice,
            BigDecimal quantity,
            String promoNote,
            boolean checked,
            OffsetDateTime date
    ) {
    }

    public record PriceSummary(
            BigDecimal lastPrice,
            String lastStore,
            OffsetDateTime lastDate,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            BigDecimal avgPrice,
            int occurrences
    ) {
    }

    /**
     * O resumo da Home.
     *
     * @param lastTrip   nulo quando nunca houve compra encerrada
     * @param monthTotal o que saiu no mercado este mês: a nota quando informada, senão a soma dos itens
     */
    public record ShoppingSummary(
            int openTrips,
            LastTrip lastTrip,
            BigDecimal monthTotal
    ) {
    }

    public record LastTrip(
            String clientId,
            String storeName,
            BigDecimal total,
            OffsetDateTime closedAt
    ) {
    }
}
