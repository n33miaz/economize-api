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

    // QUANDO NOS lemos o Pluggy. Nao diz nada sobre a idade do dado: o sync
    // pode responder 200 lendo um retrato que o Pluggy coletou do banco ha um
    // mes. Ver V37 para o caso que obrigou a separar os tres tempos.
    @Column(name = "last_synced_at")
    private OffsetDateTime lastSyncedAt;

    // Estado declarado pelo provedor: UPDATED, LOGIN_ERROR, WAITING_USER_INPUT,
    // OUTDATED. Nulo = nunca perguntamos.
    @Column(name = "status", length = 40)
    private String status;

    @Column(name = "execution_status", length = 60)
    private String executionStatus;

    @Column(name = "status_detail", length = 400)
    private String statusDetail;

    // QUANDO O PLUGGY leu o banco. E esta a idade real do que o usuario ve.
    @Column(name = "provider_updated_at")
    private OffsetDateTime providerUpdatedAt;

    // Quando pedimos coleta nova. Existe para nao pedir em rajada: o Pluggy
    // cobra por atualizacao e o banco do outro lado tem limite proprio.
    @Column(name = "update_requested_at")
    private OffsetDateTime updateRequestedAt;

    /**
     * Ha quantas horas o BANCO foi lido. Vazio quando nunca perguntamos.
     *
     * <p>Fica no modelo, e nao na tela, porque "o dado esta velho" e uma regra
     * do dominio: a mesma resposta serve o app, o relatorio e o vigia.
     */
    public java.util.OptionalLong horasDesdeAColeta() {
        if (providerUpdatedAt == null) return java.util.OptionalLong.empty();
        return java.util.OptionalLong.of(
                java.time.Duration.between(providerUpdatedAt, OffsetDateTime.now()).toHours());
    }

    /**
     * A conexao precisa de atencao do usuario?
     *
     * <p>Duas situacoes diferentes, mesma consequencia pratica: ou o provedor
     * diz que travou (credencial, segundo fator), ou ele nao trava mas tambem
     * nao coleta ha mais de um dia — foi exatamente este segundo caso que
     * deixou nove compras de cartao de fora sem nenhum erro aparecer.
     */
    public boolean precisaDeAtencao() {
        if (status != null && !"UPDATED".equalsIgnoreCase(status)) return true;
        return horasDesdeAColeta().orElse(0) > 24;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }
}
