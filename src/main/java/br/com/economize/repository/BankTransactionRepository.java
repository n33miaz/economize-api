package br.com.economize.repository;

import br.com.economize.model.BankTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {
    List<BankTransaction> findAllByUserIdOrderByDateDesc(UUID userId);

    boolean existsByUserIdAndTransactionId(UUID userId, String transactionId);

    List<BankTransaction> findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
            UUID userId, OffsetDateTime start, OffsetDateTime end);

    List<BankTransaction> findAllByUserIdAndUploadIdOrderByDateDesc(UUID userId, UUID uploadId);

    // EC-113: os lançamentos de UMA conta dentro de uma janela — a consulta que
    // monta a fatura do cartão. É o único leitor do índice
    // (account_id, date) criado na V16; o user_id continua na cláusula por
    // segurança (dono é filtro, nunca checagem posterior), não por desempenho.
    List<BankTransaction> findAllByUserIdAndAccountIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
            UUID userId, UUID accountId, OffsetDateTime start, OffsetDateTime end);

    // EC-113: carimba a origem em linhas que JÁ estavam gravadas. A dimensão de
    // conta nasceu depois do histórico, e a dedupe por id externo descarta a
    // linha nova que a traria — sem isto, "pular duplicata" viraria "o extrato
    // já sincronizado nunca terá origem" e a fatura só abriria daqui a meses.
    // Só carimba quem ainda não tem origem: decisão anterior nunca é sobrescrita.
    @Modifying
    @Transactional
    @Query("update BankTransaction t set t.accountId = :accountId "
            + "where t.user.id = :userId and t.id in :ids and t.accountId is null")
    int assignAccount(@Param("userId") UUID userId,
                      @Param("accountId") UUID accountId,
                      @Param("ids") Collection<UUID> ids);

    List<BankTransaction> findAllByUserIdAndReviewStatusInOrderByDateDesc(
            UUID userId, Collection<BankTransaction.ReviewStatus> statuses);

    List<BankTransaction> findAllByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    // Leitura de uma transação SEMPRE amarrada ao dono do token: id de outro
    // usuário não retorna linha nenhuma, e a rota responde 404 em vez de expor
    // o recurso alheio (o IDOR do EC-037 nasceu de um findById solto)
    Optional<BankTransaction> findByIdAndUserId(UUID id, UUID userId);

    long countByUserIdAndReviewStatusIn(UUID userId, Collection<BankTransaction.ReviewStatus> statuses);

    boolean existsByUserIdAndCategoryId(UUID userId, UUID categoryId);

    // Agregação da análise mensal: soma por categoria e tipo dentro da janela.
    // JPQL puro para funcionar igual no Postgres e no H2 dos testes.
    // Perna de movimentação entre contas do titular fica FORA (EC-106): pagar a
    // fatura não é despesa (a despesa foi a compra) nem o crédito que entra no
    // cartão é receita. Sem esse filtro, conectar cartão + conta corrente
    // dobrava "Despesas do mês" e inventava uma receita do tamanho da fatura.
    @Query("""
            select t.categoryId as categoryId, t.type as type,
                   sum(t.amount) as total, count(t) as txCount
            from BankTransaction t
            where t.user.id = :userId and t.date >= :start and t.date < :end
              and t.internalTransfer = false
            group by t.categoryId, t.type
            """)
    List<CategoryTotal> sumByCategory(@Param("userId") UUID userId,
                                      @Param("start") OffsetDateTime start,
                                      @Param("end") OffsetDateTime end);

    // Janela total de dados do usuário — o seletor de meses é derivado disso
    @Query("select min(t.date), max(t.date) from BankTransaction t where t.user.id = :userId")
    List<Object[]> findDateBounds(@Param("userId") UUID userId);

    // EC-106: candidatos à remarcação retroativa de perna interna. O cartão
    // costuma ser conectado DEPOIS da conta corrente, e nesse dia o pagamento de
    // fatura já está gravado como despesa comum; quando a contrapartida enfim
    // aparece, é preciso achar aquele débito antigo para corrigi-lo. O valor
    // exato (com sinal) e a janela estreita fazem a triagem barata; a âncora
    // "fatura" e o pareamento 1:1 são decididos em memória, no serviço.
    @Query("""
            select t from BankTransaction t
            where t.user.id = :userId
              and t.amount = :amount
              and t.internalTransfer = false
              and t.date >= :start and t.date < :end
            order by t.date asc
            """)
    List<BankTransaction> findUnmarkedByAmountInWindow(@Param("userId") UUID userId,
                                                       @Param("amount") java.math.BigDecimal amount,
                                                       @Param("start") OffsetDateTime start,
                                                       @Param("end") OffsetDateTime end);

    // Marca em bloco, por id: a linha corrigida foi lida fora de transação (o
    // pipeline de importação roda no boundedElastic, sem sessão aberta), então
    // um UPDATE direto é mais previsível do que remontar entidade destacada.
    @Modifying
    @Transactional
    @Query("update BankTransaction t set t.internalTransfer = true "
            + "where t.user.id = :userId and t.id in :ids")
    int markAsInternalTransfer(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    interface CategoryTotal {
        UUID getCategoryId();

        String getType();

        java.math.BigDecimal getTotal();

        long getTxCount();
    }
}
