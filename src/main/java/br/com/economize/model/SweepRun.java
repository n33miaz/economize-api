package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma passada de um vigia, com o que ela mexeu — EC-202.
 *
 * <p>Seis varreduras rodam sozinhas depois de cada importação e <b>mudam os
 * números do usuário</b>. Até aqui não deixavam rastro: nada dizia que
 * rodaram, o que acharam, nem como desfazer. Trabalho automático sem
 * prestação de contas é o que faz alguém desconfiar do app inteiro.
 */
@Entity
@Table(name = "sweep_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SweepRun {

    /**
     * Os vigias, com o nome que a tela mostra e o que cada um faz.
     *
     * <p>Enum no código e {@code VARCHAR} no banco: um vigia novo não pode
     * exigir migration, e a frase de função mora junto da regra dele.
     */
    public enum Kind {
        /** Dinheiro do titular trocando de bolso entre contas dele. */
        INTERNAL_TRANSFER,
        /** Aplicação e resgate: conta ↔ investimento do mesmo dono. */
        INVESTMENT_FLOW,
        /** Dinheiro que ficou dentro da casa. */
        FAMILY_TRANSFER,
        /** A mesma linha que entrou por duas portas. */
        DUPLICATE,
        /** A compra que voltou. */
        REFUND,
        /** As cobranças que se repetem. */
        RECURRENCE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Kind kind;

    /**
     * Quantas linhas (ou pares) esta passada mexeu.
     *
     * <p>Zero é registro legítimo e útil: "rodei e não achei nada" é resposta,
     * e sem ela o silêncio é ambíguo — o usuário não sabe se o vigia trabalhou
     * ou se quebrou.
     */
    @Column(nullable = false)
    private int affected;

    /** Volume em dinheiro, quando o vigia sabe dizer. Nulo na recorrência. */
    @Column(precision = 19, scale = 4)
    private BigDecimal volume;

    @Column(name = "ran_at", nullable = false)
    private OffsetDateTime ranAt;

    /**
     * Desfeito pelo usuário. A passada continua no histórico com a marca e
     * <b>não</b> é apagada — apagar esconderia que o vigia errou, que é
     * exatamente o que o histórico existe para mostrar.
     */
    @Column(name = "undone_at")
    private OffsetDateTime undoneAt;

    @PrePersist
    void onCreate() {
        if (this.ranAt == null) this.ranAt = OffsetDateTime.now();
    }
}
