package br.com.economize.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A fatura que o BANCO fechou — ao lado da que o app deduz, nunca no lugar.
 *
 * <p>O app monta a fatura recortando os lançamentos pelo dia de fechamento e
 * somando o que tem. É honesto e é declarado, mas só enxerga o que chegou até
 * nós: em 21/09/2026 a conta do dono mostrava R$ 775,67 num cartão cuja fatura
 * fechada, no banco, foi de R$ 2.311,49.
 *
 * <p>Guardar as duas é o que transforma essa diferença em informação. Quando
 * elas divergem, o app pode dizer "o banco fechou em X e eu só enxergo Y" —
 * que é exatamente o aviso que faltava. Substituir uma pela outra apagaria o
 * sinal.
 *
 * <p>Note o que esta tabela NÃO tem: itens. O Pluggy entrega o total da
 * fatura, não a lista de compras dela — as compras continuam sendo os
 * lançamentos, e é por isso que a conta deduzida continua existindo.
 */
@Entity
@Table(name = "card_bills")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CardBill {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** A conta de cartão. Guardada como id, como o resto do extrato faz. */
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** O id no provedor: é por ele que uma fatura em aberto é ATUALIZADA. */
    @Column(name = "external_id", nullable = false, length = 80)
    private String externalId;

    @Column(name = "closing_date")
    private LocalDate closingDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "total_amount", precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "minimum_payment", precision = 19, scale = 4)
    private BigDecimal minimumPayment;

    @Column(name = "finance_charges", precision = 19, scale = 4)
    private BigDecimal financeCharges;

    @Column(length = 3)
    private String currency;

    @Column(name = "allows_installments", nullable = false)
    private boolean allowsInstallments;

    /** Quando NÓS lemos. Fatura lida há semanas pode estar velha, e a tela tem
     * de poder dizer isso em vez de fingir atualidade. */
    @Column(name = "synced_at", nullable = false)
    private OffsetDateTime syncedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime agora = OffsetDateTime.now();
        if (createdAt == null) createdAt = agora;
        if (updatedAt == null) updatedAt = agora;
        if (syncedAt == null) syncedAt = agora;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
