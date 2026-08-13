package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "category_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryRule {

    public enum MatchType {EXACT, CONTAINS}

    // USER = regra criada explicitamente; LEARNED = gravada a partir de uma
    // correção/confirmação na revisão. A distinção pesa na confiança do motor.
    public enum Origin {USER, LEARNED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    // Descrição normalizada (DescriptionNormalizer) — a chave de matching;
    // mesmo limite do normalized_description para o aprendizado nunca estourar
    @Column(nullable = false, length = 160)
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 12)
    private MatchType matchType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Origin origin;

    @Column(nullable = false)
    private int hits;

    @Column(name = "last_hit_at")
    private OffsetDateTime lastHitAt;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
        if (this.matchType == null) this.matchType = MatchType.CONTAINS;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
