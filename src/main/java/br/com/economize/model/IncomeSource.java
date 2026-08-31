package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma fonte de renda com calendário próprio (EC-135).
 *
 * <p>O salário cai dia 5 e o vale-refeição dia 25. Tratar os dois pela mesma
 * âncora de ciclo é o que fazia a compra paga com o VR de 25/08 ser cobrada do
 * mês que estava fechando, e não do que ia abrir. Aqui cada fonte guarda o
 * próprio {@code anchorDay} — e o gasto herda o ciclo de quem o pagou.
 */
@Entity
@Table(name = "income_sources", uniqueConstraints = {
        @UniqueConstraint(name = "uq_income_sources_user_kind_name",
                columnNames = {"user_id", "kind", "name"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncomeSource {

    /**
     * MEAL_VOUCHER é o VR (refeição pronta) e FOOD_VOUCHER o VA (supermercado).
     * A distinção não é burocracia: o VA vira compra de mês inteiro e alimenta
     * a nota fiscal; o VR sai em almoços soltos.
     */
    public enum Kind {SALARY, MEAL_VOUCHER, FOOD_VOUCHER, ADVANCE, OTHER}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "expected_amount", precision = 19, scale = 4)
    private BigDecimal expectedAmount;

    @Column(name = "anchor_day")
    private Short anchorDay;

    /**
     * Sugestão do motor de recorrência não é verdade declarada. Enquanto for
     * falso, o valor não entra no cálculo de custo em horas — errar o valor da
     * hora por um salário chutado é pior do que não calcular.
     */
    @Column(nullable = false)
    private boolean confirmed;

    @Column(nullable = false)
    private boolean active;

    /** Série que originou a sugestão; nulo quando o usuário cadastrou à mão. */
    @Column(name = "series_id")
    private UUID seriesId;

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
