package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A casa (EC-149): o grupo que dois ou mais logins formam para ver os gastos
 * em conjunto sem misturar os extratos.
 *
 * <p>O grupo em si guarda pouco — nome e dono. O que importa mora no
 * {@link FamilyMember}: é cada membro quem decide o que a casa vê dele.
 */
@Entity
@Table(name = "family_groups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FamilyGroup {

    public static final String DEFAULT_NAME = "Casa";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 60)
    private String name;

    // Quem criou a casa. É quem convida, renomeia e desfaz; e é o único que
    // não sai sem levar a casa junto.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User owner;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        // UTC e microssegundos, como o TIMESTAMPTZ volta do banco — ver
        // FamilyMember.onCreate: a resposta montada da entidade recém-salva
        // tem que ser idêntica à da leitura seguinte
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = this.createdAt;
        if (this.name == null || this.name.isBlank()) this.name = DEFAULT_NAME;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
