package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Uma ida ao mercado (V39): a compra registrada enquanto acontece.
 *
 * <p>O que a distingue das outras entidades do projeto é que <b>ela nasce no
 * aparelho, sem internet</b>. Por isso a identidade que importa é
 * {@link #clientId}, gerada offline, e o {@code PUT} que a traz ao servidor é
 * idempotente por ela: a mesma viagem reenviada dez vezes (rede caindo no
 * meio do mercado) é uma viagem só. O {@code id} do servidor existe para as
 * chaves estrangeiras.
 *
 * <p>Os itens não são coleção JPA daqui de propósito: a fusão precisa deles
 * como mapa por {@code clientId}, e a listagem os carrega de uma vez para
 * todas as viagens — os dois caminhos ficam mais claros com o repositório de
 * itens do que com uma coleção preguiçosa que estoura fora da transação.
 */
@Entity
@Table(name = "shopping_trips", uniqueConstraints = {
        // a idempotência offline, gravada onde uma segunda requisição
        // concorrente não a contorna
        @UniqueConstraint(name = "uq_shopping_trips_owner_client", columnNames = {"user_id", "client_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShoppingTrip {

    /**
     * OPEN é "estou no mercado"; CLOSED é "passei no caixa"; RECONCILED é
     * "achei no extrato o lançamento que pagou isto". Só anda para a frente,
     * com uma exceção: CLOSED volta a OPEN se a pessoa lembrou de um item —
     * RECONCILED não volta, porque já está amarrado a dinheiro que saiu.
     */
    public enum Status {OPEN, CLOSED, RECONCILED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Quem abriu a viagem. Membro da casa edita, mas o dono é quem compartilha. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Preenchido = a casa inteira vê e edita. Nulo = só o dono. */
    @Column(name = "family_group_id")
    private UUID familyGroupId;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "store_name", length = 120)
    private String storeName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status;

    /** O teto que a pessoa se deu antes de entrar. Nulo = sem teto. */
    @Column(precision = 19, scale = 4)
    private BigDecimal budget;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    /**
     * O total da NOTA, informado ou lido depois. É o número oficial da
     * compra; a soma dos itens é a estimativa feita no corredor, e as duas
     * convivem para a pessoa ver a diferença.
     */
    @Column(name = "receipt_total", precision = 19, scale = 4)
    private BigDecimal receiptTotal;

    /**
     * A chave de acesso do cupom, 44 dígitos — V43.
     *
     * <p>Ela identifica a nota de forma única e carrega, sem consultar
     * ninguém, o estado, o mês, o CNPJ de quem emitiu, o modelo, a série e o
     * número impresso no papel. Os ITENS não estão aqui: eles moram no portal
     * da Fazenda de cada estado, e isso é outro projeto.
     */
    @Column(name = "receipt_key", length = 44)
    private String receiptKey;

    /** Sai da própria chave; guardado para "quanto eu gasto NESTE mercado". */
    @Column(name = "receipt_issuer_cnpj", length = 14)
    private String receiptIssuerCnpj;

    /** O lançamento do extrato que pagou esta compra, quando conciliada. */
    @Column(name = "reconciled_transaction_id")
    private UUID reconciledTransactionId;

    @Column(length = 500)
    private String notes;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public boolean isShared() {
        return familyGroupId != null;
    }

    @PrePersist
    protected void onCreate() {
        // UTC e microssegundos, como o TIMESTAMPTZ volta do banco (ver
        // FamilyMember.onCreate): a resposta do PUT sai da entidade em memória
        // e a do GET seguinte sai da leitura — o app compara os dois updatedAt
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) this.status = Status.OPEN;
        if (this.startedAt == null) this.startedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
