package br.com.economize.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Uma chave de integração (V44): a credencial com que outro sistema do dono
 * lê as rotas {@code /api/v1/integration/**}.
 *
 * <p><b>O segredo não mora aqui.</b> Só o SHA-256 dele ({@link #keyHash}) e o
 * começo ({@link #prefix}), que serve para a pessoa reconhecer a chave na
 * lista. Quem lê esta tabela não consegue se passar pelo dono.
 */
@Entity
@Table(name = "integration_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntegrationKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 60)
    private String name;

    /** SHA-256 do segredo, em hexadecimal minúsculo. */
    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(nullable = false, length = 12)
    private String prefix;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    /** Preenchido = a chave não autentica mais. Não volta atrás. */
    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    @PrePersist
    protected void onCreate() {
        // UTC e microssegundos, como o TIMESTAMPTZ volta do banco: a resposta
        // da criação sai da entidade em memória e a lista sai da leitura
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        }
    }
}
