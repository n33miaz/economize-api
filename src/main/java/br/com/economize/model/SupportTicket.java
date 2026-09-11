package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Um chamado de suporte — EC-209.
 *
 * <p><b>Por que ele mora no banco.</b> Suporte que vive num chat efêmero some
 * quando o app fecha: a pessoa descreve o problema, fecha o app, volta no dia
 * seguinte e não encontra nem o que escreveu nem se alguém leu. O efeito
 * prático é que ela desiste — o que, do ponto de vista de quem mede chamados,
 * é indistinguível de "problema resolvido".
 *
 * <p>Guardado só no aparelho ele morreria na troca de celular, no "apagar
 * dados locais" e no login em outro lugar: exatamente os momentos em que a
 * pessoa mais precisa provar que pediu ajuda.
 *
 * <p><b>O prazo é coluna, não texto de tela.</b> "Respondemos em até 2 dias
 * úteis" escrito na interface é promessa que ninguém consegue medir depois.
 * Gravado por chamado, ele vira o número que o app mostra e o que diz se a
 * promessa foi cumprida — e se a política mudar amanhã, o chamado de hoje
 * mantém a promessa de hoje.
 */
@Entity
@Table(name = "support_tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupportTicket {

    /** O assunto, de uma lista curta — lista longa vira triagem para o usuário. */
    public enum Subject {
        /** Número errado, soma que não bate, lançamento sumido. */
        NUMERO_ERRADO,
        /** Importação de extrato que falhou ou leu torto. */
        IMPORTACAO,
        /** Conexão bancária. */
        CONEXAO,
        /** Plano, cobrança, cancelamento. */
        COBRANCA,
        /** Conta, senha, segundo fator. */
        CONTA,
        /** O resto. */
        OUTRO
    }

    public enum Status {
        /** Aberto, ninguém respondeu ainda. */
        OPEN,
        /** Respondido; a pessoa ainda pode reabrir criando outro. */
        ANSWERED,
        /** Encerrado. */
        CLOSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Subject subject;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Status status = Status.OPEN;

    /** Até quando prometemos responder. Gravado na abertura, de propósito. */
    @Column(name = "respond_by", nullable = false)
    private OffsetDateTime respondBy;

    /**
     * A resposta, e quando veio. Nulo enquanto ninguém respondeu — e nulo aqui
     * é informação: é o que faz a tela dizer "aguardando" em vez de mostrar
     * uma conversa vazia.
     */
    @Column(columnDefinition = "text")
    private String answer;

    @Column(name = "answered_at")
    private OffsetDateTime answeredAt;

    /**
     * O que a pessoa estava vendo quando pediu ajuda.
     *
     * <p>Sem isto, metade dos chamados começa com uma ida e volta só para
     * descobrir a tela e a versão — e cada ida e volta custa um dia.
     */
    @Column(name = "app_version", length = 20)
    private String appVersion;

    @Column(length = 40)
    private String screen;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
        if (this.status == null) this.status = Status.OPEN;
    }

    /** O prazo já passou sem resposta? É o que a tela precisa para ser honesta. */
    public boolean isOverdue() {
        return status == Status.OPEN && respondBy != null
                && respondBy.isBefore(OffsetDateTime.now());
    }
}
