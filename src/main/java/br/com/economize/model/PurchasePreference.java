package br.com.economize.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Como a pessoa faz as compras de mercado (V38).
 *
 * <p>É 1:1 com o usuário, por isso a chave primária é a própria chave
 * estrangeira — o mesmo desenho de {@link WorkProfile}. <b>Ausência é
 * informação:</b> sem linha aqui, o app deduz a cadência pelo extrato e diz
 * que está deduzindo. A preferência declarada sempre vence a dedução.
 *
 * <p>Só guarda o que o extrato não conta. O padrão de entradas (em que dia
 * útil o salário cai) é derivado a cada leitura e nunca é gravado.
 */
@Entity
@Table(name = "purchase_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchasePreference {

    public enum Cadence {MONTHLY, WEEKLY}

    public enum PaymentMode {CASH, CARD}

    @Id
    @Column(name = "user_id")
    private UUID userId;

    /** Texto no banco (como {@code sweep_runs.kind}); validado no service. */
    @Column(nullable = false, length = 8)
    private String cadence;

    @Column(name = "weekend_preferred", nullable = false)
    private boolean weekendPreferred;

    /** CASH ou CARD; nulo = o app decide. */
    @Column(name = "payment_mode", length = 8)
    private String paymentMode;

    /** O cartão cujo fechamento define a fatura, quando {@code paymentMode} é CARD. */
    @Column(name = "card_account_id")
    private UUID cardAccountId;

    /** SALARY / MEAL_VOUCHER / FOOD_VOUCHER; nulo = o app escolhe (VA > VR > salário). */
    @Column(name = "funding_kind", length = 16)
    private String fundingKind;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
