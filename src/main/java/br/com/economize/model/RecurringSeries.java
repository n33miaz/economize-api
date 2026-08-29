package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "recurring_series", uniqueConstraints = {
        @UniqueConstraint(name = "uq_recurring_series_user_key_flow",
                columnNames = {"user_id", "merchant_key", "flow"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecurringSeries {

    // INTERNAL cobre movimentação do próprio titular entre bancos: é recorrente,
    // mas não é gasto nem renda de terceiro — a previsão de saldo a ignora
    public enum Flow {EXPENSE, INCOME, INTERNAL}

    public enum Cadence {MONTHLY, WEEKLY, QUARTERLY, IRREGULAR}

    // FIXED = assinatura/plano (valor idêntico); VARIABLE = conta de consumo
    // (âncora textual fixa, valor que muda) — a variação não descarta a série
    public enum AmountType {FIXED, VARIABLE}

    public enum Source {DETECTED, USER}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Chave normalizada da entidade (token dominante ou âncora, ex. "sabesp",
    // "spotify", "fatura") — estável mesmo quando o banco troca o rótulo
    @Column(name = "merchant_key", nullable = false, length = 160)
    private String merchantKey;

    @Column(name = "display_name", length = 160)
    private String displayName;

    @Column(name = "category_id")
    private UUID categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Flow flow;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Cadence cadence;

    // Dia do mês típico da cobrança; a tolerância absorve virada de mês e
    // deslizes de fim de semana (fatura dia 02-07, Spotify dia 30-07)
    @Column(name = "anchor_day")
    private Short anchorDay;

    @Column(name = "day_tolerance")
    private Short dayTolerance;

    @Enumerated(EnumType.STRING)
    @Column(name = "amount_type", nullable = false, length = 10)
    private AmountType amountType;

    @Column(name = "expected_amount", precision = 19, scale = 4)
    private BigDecimal expectedAmount;

    @Column(nullable = false)
    private int occurrences;

    @Column(name = "first_seen_at")
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(nullable = false)
    private boolean active;

    // Descarte explícito do usuário (DELETE de série detectada): diferente da
    // desativação por staleness, o descarte nunca é revertido pela varredura —
    // evidência nova não ressuscita o que o usuário mandou embora
    @Column(nullable = false)
    private boolean dismissed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Source source;

    // Vigência do agendamento manual (source=USER): a previsão não projeta
    // antes de startsAt nem depois de endsAt. Detectadas ficam com NULL — a
    // vigência delas é o próprio histórico de ocorrências.
    @Column(name = "starts_at")
    private LocalDate startsAt;

    @Column(name = "ends_at")
    private LocalDate endsAt;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
        if (this.source == null) this.source = Source.DETECTED;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
