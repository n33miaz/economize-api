package br.com.economize.model;

import br.com.economize.service.ai.AiProvider;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Configuração de IA de um usuário — EC-107. Uma linha por conta: qual provedor,
 * qual modelo e a chave DELE, cifrada.
 *
 * <p>Quem não tem linha aqui continua usando a chave do servidor exatamente como
 * antes deste ticket; a ausência é um estado válido e permanente, não um cadastro
 * pela metade.
 *
 * <p><b>Nada nesta entidade pode virar texto sozinho.</b> {@code @ToString} está
 * fora de propósito — o Lombok geraria um toString com o envelope cifrado, e
 * envelope cifrado em log é rastro de segredo mesmo sem ser o segredo. Pelo mesmo
 * motivo o campo cifrado nunca sai daqui direto para DTO.
 */
@Entity
@Table(name = "user_ai_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAiSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // OneToOne e não ManyToOne: o unique de user_id no schema diz que a conta
    // tem no máximo uma configuração ativa
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiProvider provider;

    @Column(nullable = false, length = 80)
    private String model;

    /** Envelope "v1:&lt;keyId&gt;:&lt;iv&gt;:&lt;cifra&gt;" — ver SecretCipher. */
    @Column(name = "api_key_cipher", nullable = false, length = 1024)
    private String apiKeyCipher;

    /** Cópia do id da chave-mestra do envelope, para o COUNT de rotação. */
    @Column(name = "master_key_id", nullable = false, length = 32)
    private String masterKeyId;

    /** Últimos 4 caracteres em claro — pista de reconhecimento, ver V17. */
    @Column(name = "api_key_last4", length = 4)
    private String apiKeyLast4;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
