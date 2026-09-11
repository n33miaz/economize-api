package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Um aporte numa meta — EC-205.
 *
 * <p><b>Por que isto existe.</b> {@code wishes.saved_amount} era um número que
 * a pessoa digitava. Ele respondia "quanto já juntei" e não respondia nada
 * sobre <i>como</i> chegou ali: quando subiu, de quanto foi cada entrada, nem
 * como desfazer um erro sem recalcular de cabeça. É o mesmo defeito que o
 * EC-202 fechou nas varreduras — número que muda sem deixar recado — e aqui
 * era pior, porque quem muda é a própria pessoa, e ela esquece.
 *
 * <p><b>A tabela é o extrato; a coluna continua sendo o saldo.</b>
 * {@code saved_amount} segue existindo e é o que a projeção lê — recalcular a
 * soma a cada leitura custaria uma agregação por desejo em toda abertura da
 * tela. Estas linhas explicam aquele número.
 */
@Entity
@Table(name = "wish_contributions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WishContribution {

    /**
     * De onde veio o dinheiro deste aporte.
     *
     * <p>A distinção não é enfeite: <b>medido</b> é um número que o app apurou
     * do extrato (a sobra do ciclo), <b>declarado</b> é um número que a pessoa
     * afirmou. Apresentar o segundo como se fosse o primeiro é exatamente o
     * que o EC-206 proíbe na previsão, e a mesma regra vale aqui.
     */
    public enum Origin {
        /** A sobra de um ciclo, apurada pelo app. */
        MEASURED,
        /** A pessoa digitou o valor. */
        DECLARED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "wish_id", nullable = false)
    private UUID wishId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Positivo guarda, negativo devolve.
     *
     * <p>Não existe aporte de zero (o banco recusa). Quem errou o valor
     * corrige com um aporte NEGATIVO — que é o desfazer honesto: sai do saldo
     * e fica no extrato, em vez de apagar a linha e fingir que nunca houve.
     */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Origin origin;

    /**
     * O ciclo de onde a sobra saiu, no formato {@code YYYY-MM}. Nulo no aporte
     * digitado — e nulo aqui é informação: quer dizer "não veio de ciclo
     * nenhum", nunca "esqueci de gravar".
     */
    @Column(name = "cycle_month", length = 7)
    private String cycleMonth;

    @Column(length = 200)
    private String note;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
        if (this.origin == null) this.origin = Origin.DECLARED;
    }
}
