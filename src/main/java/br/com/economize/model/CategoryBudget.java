package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * O teto que o dono pôs numa categoria — EC-204.
 *
 * <p>Tabela própria, e não coluna em {@code categories}, porque a categoria é
 * compartilhada: as do sistema servem a todo mundo, e um teto gravado nelas
 * seria o limite de uma pessoa valendo para todas.
 *
 * <p>O limite é <b>mensal</b> mesmo quando o usuário lê o gasto por ciclo de
 * fatura. A intenção — "não quero passar de R$ 800 em mercado" — é mensal;
 * o recorte é escolha de tela e pode mudar amanhã. Quem faz a regra de três
 * com o tamanho da janela é o serviço, não o dado.
 */
@Entity
@Table(name = "category_budgets", uniqueConstraints = {
        @UniqueConstraint(name = "uq_category_budgets_user_category",
                columnNames = {"user_id", "category_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryBudget {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    /**
     * Sempre positivo: é teto de GASTO, e o extrato guarda despesa em negativo.
     * Misturar os dois sinais aqui garantiria uma comparação invertida em
     * algum lugar meses depois.
     */
    @Column(name = "monthly_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal monthlyLimit;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime agora = OffsetDateTime.now();
        this.createdAt = agora;
        this.updatedAt = agora;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
