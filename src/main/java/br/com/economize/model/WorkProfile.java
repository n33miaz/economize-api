package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A jornada de trabalho (EC-141) — o divisor que transforma dinheiro em tempo
 * de vida.
 *
 * <p>É 1:1 com o usuário, por isso a chave primária é a própria chave
 * estrangeira. <b>Ausência é informação:</b> sem perfil, o custo em horas é
 * desconhecido e a API devolve nulo. Assumir 8h/5d inventaria uma hora que não
 * é a da pessoa — e o número inteiro do produto depende dessa hora estar certa.
 */
@Entity
@Table(name = "work_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "days_per_week", nullable = false)
    private short daysPerWeek;

    /** Decimal porque 6h30 existe; inteiro arredondaria a jornada de alguém. */
    @Column(name = "hours_per_day", nullable = false, precision = 4, scale = 2)
    private BigDecimal hoursPerDay;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
