package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Vínculo série ↔ transação em tabela própria: {@code bank_transactions} não
 * pode ser alterada por este ticket, e o UNIQUE em {@code bank_transaction_id}
 * garante que uma transação nunca conta em duas séries.
 */
@Entity
@Table(name = "recurring_series_links")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecurringSeriesLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "series_id", nullable = false)
    private UUID seriesId;

    @Column(name = "bank_transaction_id", nullable = false, unique = true)
    private UUID bankTransactionId;

    @Column(name = "matched_at")
    private OffsetDateTime matchedAt;

    @PrePersist
    protected void onCreate() {
        if (this.matchedAt == null) this.matchedAt = OffsetDateTime.now();
    }
}
