package br.com.economize.model;

import jakarta.persistence.*;
import lombok.*;

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
