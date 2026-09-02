package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Um membro da casa (EC-149) — e, junto, <b>o que ele compartilha</b>.
 *
 * <p>Os parâmetros ficam no membro, não no grupo, porque são decisão
 * individual: na mesma casa, ela pode abrir as linhas e ele mostrar só os
 * totais. Sair da casa apaga a linha inteira, parâmetros inclusive — nada fica
 * "lembrado" para uma casa futura.
 *
 * <p>As duas coleções são {@code @ElementCollection} de ids, e não entidades:
 * são listas de referência (o que ocultar, o que mostrar) sem ciclo de vida
 * próprio, e o {@code PUT /family/sharing} as substitui por inteiro. Carregam
 * EAGER porque toda leitura do membro precisa delas para montar a consulta
 * compartilhada, e são unidades de ids — não vale uma segunda ida ao banco.
 */
@Entity
@Table(name = "family_members", uniqueConstraints = {
        // um grupo por usuário (v1) — a regra do modelo, no banco
        @UniqueConstraint(name = "uq_family_members_user", columnNames = {"user_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FamilyMember {

    /** OWNER criou a casa; MEMBER entrou por convite. */
    public enum Role {OWNER, MEMBER}

    /**
     * NONE não mostra nada; TOTALS mostra somas por categoria e do período, mas
     * nenhuma linha; TRANSACTIONS abre as linhas. Compartilhar é opt-in
     * progressivo — e o padrão ao entrar é TOTALS.
     */
    public enum ShareScope {NONE, TOTALS, TRANSACTIONS}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private FamilyGroup group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "share_scope", nullable = false, length = 16)
    private ShareScope shareScope;

    /**
     * Quando {@link #sharedAccountIds} restringe as contas, as linhas SEM conta
     * (upload manual, {@code accountId} nulo) só entram se isto for verdadeiro.
     */
    @Column(name = "include_unassigned", nullable = false)
    @Builder.Default
    private boolean includeUnassigned = true;

    /**
     * Categorias que saem da visão da casa — das linhas e das somas. O total do
     * membro na casa é recalculado sem elas, senão o outro deduz o valor
     * escondido pela diferença.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "family_member_hidden_categories",
            joinColumns = @JoinColumn(name = "member_id", nullable = false))
    @Column(name = "category_id", nullable = false)
    @Builder.Default
    private Set<UUID> hiddenCategoryIds = new HashSet<>();

    /** Contas que a casa vê. Vazio = todas. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "family_member_shared_accounts",
            joinColumns = @JoinColumn(name = "member_id", nullable = false))
    @Column(name = "account_id", nullable = false)
    @Builder.Default
    private Set<UUID> sharedAccountIds = new HashSet<>();

    @Column(name = "joined_at", updatable = false)
    private OffsetDateTime joinedAt;

    public boolean isOwner() {
        return role == Role.OWNER;
    }

    @PrePersist
    protected void onCreate() {
        // Em UTC e truncado a microssegundos, que é como o TIMESTAMPTZ volta do
        // banco: o 201 de criar/entrar sai da entidade em memória e o GET
        // seguinte sai da leitura — com o offset da máquina e nanos, o mesmo
        // joinedAt aparecia com dois textos diferentes para o mesmo instante
        this.joinedAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (this.shareScope == null) this.shareScope = ShareScope.TOTALS;
    }
}
