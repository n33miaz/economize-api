package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Um item do carrinho (V39).
 *
 * <p><b>Item não se apaga, se marca.</b> Dois aparelhos da mesma casa editam
 * a mesma lista sem se ver. Se apagar fosse {@code DELETE}, o outro aparelho
 * reenviaria o item que ainda tem e ele ressuscitaria. {@link #deleted} é a
 * lápide: viaja na resposta, e o outro lado apaga também.
 *
 * <p><b>Quem decide a fusão é o relógio do aparelho.</b>
 * {@link #clientUpdatedAt} é a hora da última edição no aparelho que editou;
 * na fusão, o item que chega só substitui o gravado se for mais novo. É
 * last-write-wins por item — não por campo —, porque é o que dá para explicar
 * para a pessoa: "a edição da Ana ficou, porque foi a última".
 */
@Entity
@Table(name = "shopping_items", uniqueConstraints = {
        @UniqueConstraint(name = "uq_shopping_items_trip_client", columnNames = {"trip_id", "client_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShoppingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(nullable = false, length = 120)
    private String name;

    /** Minúsculo, sem acento, espaços colapsados: a chave do histórico de preços. */
    @Column(name = "normalized_name", nullable = false, length = 120)
    private String normalizedName;

    /** Três casas porque balança pesa em gramas: 0,750 kg de queijo. */
    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity;

    /** Nulo enquanto a pessoa só pegou e ainda não anotou o preço. */
    @Column(name = "unit_price", precision = 19, scale = 4)
    private BigDecimal unitPrice;

    /** A promoção como estava escrita na gôndola: "leve 3 pague 2". */
    @Column(name = "promo_note", length = 200)
    private String promoNote;

    /**
     * Pegou (entra no total). Falso = viu o preço e deixou na prateleira:
     * fica registrado para o histórico, mas não soma.
     */
    @Column(nullable = false)
    private boolean checked;

    /** Referência LOCAL da foto no aparelho. A imagem não sobe nesta versão. */
    @Column(name = "photo_ref", length = 200)
    private String photoRef;

    /** Quem colocou o item no carrinho — na casa, responde "quem pegou isso?". */
    @Column(name = "added_by")
    private UUID addedBy;

    /** Lápide: o item foi removido e a remoção precisa chegar aos outros aparelhos. */
    @Column(nullable = false)
    private boolean deleted;

    /** O relógio do aparelho na última edição: quem decide a fusão. */
    @Column(name = "client_updated_at", nullable = false)
    private OffsetDateTime clientUpdatedAt;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /** Conta no total: pegou, não foi removido e tem preço anotado. */
    public boolean countsTowardsTotal() {
        return checked && !deleted && unitPrice != null;
    }

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
        if (this.quantity == null) this.quantity = BigDecimal.ONE;
        if (this.clientUpdatedAt == null) this.clientUpdatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
