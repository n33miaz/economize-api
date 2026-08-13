package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    // EXPENSE/INCOME orientam a tela de análise; BOTH cobre transferências e ajustes
    public enum Flow {EXPENSE, INCOME, BOTH}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // NULL = seed do sistema, visível para todos; preenchido = categoria do usuário
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 60)
    private String slug;

    @Column(name = "group_name", length = 60)
    private String groupName;

    // Hierarquia de no máximo 2 níveis: quem tem pai não pode ser pai
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Flow flow;

    @Column(length = 9)
    private String color;

    @Column(length = 40)
    private String icon;

    // Elo com o enum legado TransactionCategory: permite backfill e fallback keyword
    @Column(name = "system_key", length = 32)
    private String systemKey;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
        if (this.flow == null) this.flow = Flow.EXPENSE;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public boolean isRoot() {
        return this.parent == null;
    }

    /** O pai quando é subcategoria, ela mesma quando é raiz — a unidade de leitura da análise. */
    public Category rootCategory() {
        return this.parent != null ? this.parent : this;
    }
}
