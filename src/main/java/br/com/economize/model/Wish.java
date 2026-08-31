package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Um desejo (EC-140) — e, se amadurecer, a meta e a compra que ele virou.
 *
 * <p>O ciclo desejo → meta → compra é o MESMO objeto mudando de
 * {@link Status}, não três tabelas. Mover de tabela perderia justamente o que
 * dá sentido ao histórico: há quanto tempo a pessoa queria aquilo.
 */
@Entity
@Table(name = "wishes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Wish {

    /**
     * WISH é "eu quero"; GOAL é "estou guardando para isso". A diferença
     * importa: só a meta compete pela sobra do mês.
     */
    public enum Status {WISH, GOAL, PURCHASED, ARCHIVED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "target_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal targetAmount;

    @Column(name = "saved_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal savedAmount;

    @Column(name = "category_id")
    private UUID categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status;

    /** O prazo que o usuário QUER. A data que o dinheiro permite é calculada. */
    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(length = 400)
    private String note;

    @Column(name = "purchased_at")
    private LocalDate purchasedAt;

    /** Transação do extrato que confirmou a compra, quando houver. */
    @Column(name = "purchase_transaction_id")
    private UUID purchaseTransactionId;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
        if (this.status == null) this.status = Status.WISH;
        if (this.savedAmount == null) this.savedAmount = BigDecimal.ZERO;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
