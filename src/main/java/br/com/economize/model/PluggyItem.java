package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Conexão (item) do Pluggy vinculada a uma conta do Economize — EC-106.
 * Guarda só a referência e a identificação da instituição: credenciais da
 * aplicação e segredos nunca passam por aqui.
 */
@Entity
@Table(name = "pluggy_items", uniqueConstraints = {
        @UniqueConstraint(name = "uq_pluggy_items_item_id", columnNames = {"item_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PluggyItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // id do item na API do Pluggy — texto para não acoplar ao formato deles
    @Column(name = "item_id", nullable = false, length = 64)
    private String itemId;

    // instituição conectada, copiada do connector no registro: a listagem do
    // app não precisa de chamada ao Pluggy
    @Column(name = "connector_id")
    private Long connectorId;

    @Column(name = "connector_name", length = 160)
    private String connectorName;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_synced_at")
    private OffsetDateTime lastSyncedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }
}
