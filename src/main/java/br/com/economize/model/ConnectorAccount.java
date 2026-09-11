package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Conta de origem de um lançamento — EC-113. É a resposta para "de onde veio
 * esta linha": conta bancária ou cartão de crédito, de qual instituição, dentro
 * de qual conexão do usuário.
 *
 * <p>Existe uma linha por conta do provedor, criada/atualizada na sincronização.
 * O upload manual de arquivo NÃO cria conta nenhuma: ali a origem é
 * genuinamente desconhecida e o lançamento fica com {@code accountId} nulo.
 */
@Entity
@Table(name = "connector_accounts", uniqueConstraints = {
        @UniqueConstraint(name = "uq_connector_accounts_provider",
                columnNames = {"user_id", "provider_account_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConnectorAccount {

    /**
     * Tipo da conta, no vocabulário do PROVEDOR e não no do produto bancário.
     * BANK cobre corrente e poupança — o agregador não distingue de forma
     * confiável, e gravar "conta corrente" numa poupança seria uma mentira
     * permanente no banco de dados. CREDIT_CARD é o que abre fatura.
     */
    public enum AccountType {BANK, CREDIT_CARD}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Referência solta (UUID, sem @ManyToOne) para a conexão que trouxe a conta,
    // no mesmo estilo de categoryId/uploadId em BankTransaction: o vínculo é
    // informativo e some quando o usuário desvincula a instituição, sem que a
    // conta — nem os lançamentos dela — deixem de existir.
    @Column(name = "pluggy_item_id")
    private UUID pluggyItemId;

    @Column(name = "provider_account_id", nullable = false, length = 64)
    private String providerAccountId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 160)
    private String institution;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    // Dia do mês em que a fatura fecha / vence, derivados das datas que o
    // provedor devolve na conta. Nulos quando ele não informa — e é essa
    // ausência que faz a fatura cair no ciclo do calendário, declarando na
    // resposta que derivou assim.
    @Column(name = "statement_closing_day")
    private Integer statementClosingDay;

    @Column(name = "statement_due_day")
    private Integer statementDueDay;

    /**
     * O saldo que a INSTITUIÇÃO informou na última leitura — a segunda fonte.
     *
     * <p>Todo número de saldo do app nasce da soma dos lançamentos importados.
     * Quando falta um lançamento, o número fica errado e nada no sistema sabe
     * disso, porque não há com quem discordar. Esta coluna é esse alguém.
     *
     * <p>Nulo é estado legítimo: nem todo provedor informa, e conta que ainda
     * não sincronizou depois do EC-196 não tem leitura nenhuma. Inventar zero
     * aqui seria criar exatamente a mentira que a coluna existe para denunciar.
     */
    @Column(name = "reported_balance", precision = 19, scale = 4)
    private BigDecimal reportedBalance;

    /** Quando esse saldo foi lido. Sem a hora, o valor não serve de prova. */
    @Column(name = "reported_balance_at")
    private OffsetDateTime reportedBalanceAt;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    public boolean isCreditCard() {
        return type == AccountType.CREDIT_CARD;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }
}
