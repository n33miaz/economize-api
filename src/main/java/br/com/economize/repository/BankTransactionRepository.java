package br.com.economize.repository;

import br.com.economize.model.BankTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {
    /**
     * Quantas linhas de extrato o usuário tem. O contador do Perfil vinha do
     * tamanho da lista inteira (100 KB por abertura para desenhar um número).
     */
    long countByUserId(UUID userId);

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

    /**
     * Muda a origem de TODAS as linhas de uma conta para outra — a fusão de
     * origens duplicadas.
     *
     * <p>Diferente do {@link #assignAccount} logo acima, este SOBRESCREVE: o
     * ponto é justamente tirar as linhas de uma origem que vai deixar de
     * existir. A conta de destino é validada no serviço; aqui a única guarda é
     * o dono, para uma requisição não mexer no extrato de outra pessoa.
     *
     * <p>Existe porque a chave estrangeira de `bank_transactions.account_id` é
     * {@code ON DELETE SET NULL}: apagar a origem sem mudar as linhas antes
     * transformaria 1.632 lançamentos em lançamentos sem origem, que é pior do
     * que a duplicata que a fusão vem resolver.
     */
    @Modifying
    @Transactional
    @Query("update BankTransaction t set t.accountId = :targetId "
            + "where t.user.id = :userId and t.accountId = :sourceId")
    int moveAccount(@Param("userId") UUID userId,
                    @Param("sourceId") UUID sourceId,
                    @Param("targetId") UUID targetId);

    /** Quantos lançamentos moram nesta origem. Alimenta a sugestão de fusão. */
    long countByUserIdAndAccountId(UUID userId, UUID accountId);

    /**
     * Carimba a origem em TODAS as linhas de um upload que ainda não a tenham.
     *
     * <p>Existe para o histórico importado ANTES de a conta ser criada: sem
     * isto, a única saída seria apagar os lançamentos e reimportar o arquivo —
     * e a reimportação é idempotente por hash, então nem isso funcionaria.
     * Continua valendo a regra do carimbo por id: decisão de origem já tomada
     * nunca é sobrescrita.
     */
    @Modifying
    @Transactional
    @Query("update BankTransaction t set t.accountId = :accountId "
            + "where t.user.id = :userId and t.uploadId = :uploadId and t.accountId is null")
    int assignAccountToUpload(@Param("userId") UUID userId,
                              @Param("accountId") UUID accountId,
                              @Param("uploadId") UUID uploadId);

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
    //
    // O total é o LÍQUIDO de cada linha (valor + o que dela voltou, V40) com o
    // mesmo piso de BankTransaction.getNetAmount(): o líquido nunca troca de
    // sinal. Compra de 100 com 120 abatidos (dado de antes do teto na
    // varredura) vale zero, e não +20 abatendo as outras compras da categoria.
    // O piso é por LINHA, dentro do sum — em volta da soma ele não seguraria
    // nada. As duas consultas de soma abaixo repetem o mesmo case.
    @Query("""
            select t.categoryId as categoryId, t.type as type,
                   sum(case when t.amount < 0 and t.amount + t.refundedAmount > 0 then 0
                            when t.amount > 0 and t.amount + t.refundedAmount < 0 then 0
                            else t.amount + t.refundedAmount end) as total,
                   count(t) as txCount
            from BankTransaction t
            where t.user.id = :userId and t.date >= :start and t.date < :end
              and t.internalTransfer = false
              and t.refunded = false
              and t.ignored = false
            group by t.categoryId, t.type
            """)
    List<CategoryTotal> sumByCategory(@Param("userId") UUID userId,
                                      @Param("start") OffsetDateTime start,
                                      @Param("end") OffsetDateTime end);

    // ------------------------------------------------------------------
    // EC-149: a VISÃO DA CASA. As duas consultas abaixo são a soma e a listagem
    // de um membro com os parâmetros DELE (categorias ocultas, contas
    // compartilhadas, "extrato importado") aplicados AQUI, na cláusula — e não
    // em memória, depois de ler tudo. É a privacidade por construção da §7: o
    // que o membro escolheu não mostrar nunca sai do banco, então nenhum passo
    // posterior (soma, ordenação, serialização) tem como deixá-lo escapar.
    //
    // Regras da cláusula, iguais nas duas consultas:
    //  - perna interna fora, como na pessoal (EC-106);
    //  - categoria oculta sai; linha SEM categoria NÃO é oculta (só as listadas
    //    são) — daí o "is null or not in";
    //  - lista de contas vazia = todas (allAccounts); preenchida = só as
    //    listadas; a linha SEM conta (upload manual) entra apenas se
    //    includeUnassigned, em qualquer dos dois casos.
    //
    // O `in :lista` com coleção VAZIA é o problema: renderiza "in ()", que não
    // é SQL válido. Por isso a entrada pública são os métodos default
    // (sumByCategoryShared / findSharedInWindow), que trocam a coleção vazia
    // por um sentinela — um UUID nulo (0000...) que nenhuma linha carrega, e
    // portanto não muda o resultado: "not in (sentinela)" é verdade para todas
    // e "in (sentinela)" só é consultado quando allAccounts é falso.
    // ------------------------------------------------------------------

    UUID NO_MATCH_SENTINEL = new UUID(0L, 0L);

    @Query("""
            select t.categoryId as categoryId, t.type as type,
                   sum(case when t.amount < 0 and t.amount + t.refundedAmount > 0 then 0
                            when t.amount > 0 and t.amount + t.refundedAmount < 0 then 0
                            else t.amount + t.refundedAmount end) as total,
                   count(t) as txCount
            from BankTransaction t
            where t.user.id = :userId and t.date >= :start and t.date < :end
              and t.internalTransfer = false
              and t.refunded = false
              and t.ignored = false
              and t.familyTransfer = false
              and (t.categoryId is null or t.categoryId not in :hiddenCategoryIds)
              and ((t.accountId is null and :includeUnassigned = true)
                   or (t.accountId is not null
                       and (:allAccounts = true or t.accountId in :sharedAccountIds)))
            group by t.categoryId, t.type
            """)
    List<CategoryTotal> sumByCategoryFiltered(@Param("userId") UUID userId,
                                              @Param("start") OffsetDateTime start,
                                              @Param("end") OffsetDateTime end,
                                              @Param("hiddenCategoryIds") Collection<UUID> hiddenCategoryIds,
                                              @Param("allAccounts") boolean allAccounts,
                                              @Param("sharedAccountIds") Collection<UUID> sharedAccountIds,
                                              @Param("includeUnassigned") boolean includeUnassigned);

    @Query("""
            select t from BankTransaction t
            where t.user.id = :userId and t.date >= :start and t.date < :end
              and t.internalTransfer = false
              and t.refunded = false
              and t.ignored = false
              and t.familyTransfer = false
              and (t.categoryId is null or t.categoryId not in :hiddenCategoryIds)
              and ((t.accountId is null and :includeUnassigned = true)
                   or (t.accountId is not null
                       and (:allAccounts = true or t.accountId in :sharedAccountIds)))
            order by t.date desc
            """)
    List<BankTransaction> findSharedFiltered(@Param("userId") UUID userId,
                                             @Param("start") OffsetDateTime start,
                                             @Param("end") OffsetDateTime end,
                                             @Param("hiddenCategoryIds") Collection<UUID> hiddenCategoryIds,
                                             @Param("allAccounts") boolean allAccounts,
                                             @Param("sharedAccountIds") Collection<UUID> sharedAccountIds,
                                             @Param("includeUnassigned") boolean includeUnassigned);

    /**
     * Soma por categoria do que UM membro mostra à casa na janela. Coleções
     * vazias têm o significado natural: nenhuma categoria oculta, todas as
     * contas.
     */
    default List<CategoryTotal> sumByCategoryShared(UUID userId, OffsetDateTime start, OffsetDateTime end,
                                                    Collection<UUID> hiddenCategoryIds,
                                                    Collection<UUID> sharedAccountIds,
                                                    boolean includeUnassigned) {
        return sumByCategoryFiltered(userId, start, end,
                orSentinel(hiddenCategoryIds),
                sharedAccountIds.isEmpty(), orSentinel(sharedAccountIds),
                includeUnassigned);
    }

    /** As linhas que UM membro mostra à casa na janela, mais recente primeiro. */
    default List<BankTransaction> findSharedInWindow(UUID userId, OffsetDateTime start, OffsetDateTime end,
                                                     Collection<UUID> hiddenCategoryIds,
                                                     Collection<UUID> sharedAccountIds,
                                                     boolean includeUnassigned) {
        return findSharedFiltered(userId, start, end,
                orSentinel(hiddenCategoryIds),
                sharedAccountIds.isEmpty(), orSentinel(sharedAccountIds),
                includeUnassigned);
    }

    private static Collection<UUID> orSentinel(Collection<UUID> ids) {
        return ids.isEmpty() ? List.of(NO_MATCH_SENTINEL) : ids;
    }

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
              and t.refunded = false
              and t.ignored = false
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

    /**
     * Marca em bloco a transferência entre pessoas da mesma casa (V28). Sai só
     * das somas da CASA — na análise pessoal do dono da linha o dinheiro entrou
     * mesmo, e escondê-lo dele seria mentir sobre o extrato que ele tem na mão.
     */
    @Modifying
    @Transactional
    @Query("update BankTransaction t set t.familyTransfer = true "
            + "where t.user.id = :userId and t.id in :ids")
    int markAsFamilyTransfer(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    // ------------------------------------------------------------------
    // A conferência de saldo (BalanceReconciliationService). Ela roda ao abrir
    // o extrato e carregava TODOS os lançamentos do usuário para responder
    // duas perguntas por conta; as duas consultas abaixo respondem as mesmas
    // perguntas no banco, pelo índice (account_id, date) da V16, sem
    // materializar lançamento nenhum.
    //
    // As cláusulas são as do filtro em memória que elas substituem, linha por
    // linha — inclusive o que parece esquecimento. NÃO "alinhe" com a regra
    // das somas da Análise: aqui a pergunta é sobre o saldo da conta, e perna
    // interna e par de estorno mexem no saldo de verdade.
    // ------------------------------------------------------------------

    /**
     * Quais destas contas tiveram movimento material depois de {@code since}.
     *
     * <p>Movimento é qualquer linha da conta — ignorada, interna ou estornada
     * também: a pergunta é se a conta está viva, não se a linha entra em soma.
     * O piso é por LINHA e sobre o valor bruto; "depois" é estrito.
     *
     * <p>Nunca chame com a lista vazia: {@code in ()} não é SQL válido.
     */
    @Query("""
            select distinct t.accountId from BankTransaction t
            where t.user.id = :userId and t.accountId in :accountIds
              and t.date > :since
              and abs(t.amount) >= :floor
            """)
    List<UUID> findAccountsWithMovementSince(@Param("userId") UUID userId,
                                             @Param("accountIds") Collection<UUID> accountIds,
                                             @Param("since") OffsetDateTime since,
                                             @Param("floor") BigDecimal floor);

    /**
     * Soma do que entrou na conta DEPOIS de {@code after}, sem as ignoradas.
     *
     * <p>Só a ignorada sai: é a única linha que não é dinheiro de verdade (a
     * duplicata de outra fonte). Valor bruto, "depois" estrito. Devolve nulo
     * quando não há linha nenhuma — quem chama trata como zero.
     */
    @Query("""
            select sum(t.amount) from BankTransaction t
            where t.user.id = :userId and t.accountId = :accountId
              and t.date > :after
              and t.ignored = false
            """)
    BigDecimal sumByAccountAfter(@Param("userId") UUID userId,
                                 @Param("accountId") UUID accountId,
                                 @Param("after") OffsetDateTime after);

    /**
     * Marca a COMPRA de um estorno inteiro (V29): ela só recebe a marca — quem
     * quiser o caminho de volta chega pelo {@code refundOfId} do crédito. O
     * crédito é marcado e ligado por {@link #claimRefund}, um a um.
     */
    @Modifying
    @Transactional
    @Query("update BankTransaction t set t.refunded = true "
            + "where t.user.id = :userId and t.id in :ids")
    int markAsRefundPair(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    // ------------------------------------------------------------------
    // EC-202: o DESFAZER de uma passada de vigia. Soltam exatamente os ids
    // que aquela passada marcou — nunca uma varredura inteira. Uma linha que
    // o usuário marcou à mão depois continua marcada, porque decisão de gente
    // vence varredura em qualquer direção, e o conjunto de ids gravado na
    // passada é o que garante isso.
    // ------------------------------------------------------------------

    @Modifying
    @Transactional
    @Query("update BankTransaction t set t.internalTransfer = false "
            + "where t.user.id = :userId and t.id in :ids")
    int clearInternalTransfer(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    @Modifying
    @Transactional
    @Query("update BankTransaction t set t.familyTransfer = false "
            + "where t.user.id = :userId and t.id in :ids")
    int clearFamilyTransfer(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    /**
     * Solta a marca de descarte E o motivo. Sem limpar o motivo, a linha
     * voltaria para as somas carregando "descartada porque duplicada", e a
     * próxima varredura leria isso como decisão já tomada.
     */
    @Modifying
    @Transactional
    @Query("update BankTransaction t set t.ignored = false, t.ignoredReason = null "
            + "where t.user.id = :userId and t.id in :ids")
    int clearIgnored(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    // ------------------------------------------------------------------
    // O DESFAZER de uma passada do vigia do estorno. Entrada pública:
    // releaseRefunds. As consultas abaixo dela são as peças.
    //
    // Até 12/11/2026 era um UPDATE só, que soltava as linhas da passada e
    // ZERAVA o abate da compra. Só que o abate é a soma de várias passadas:
    // compra de 100 com 60 abatidos na passada A e 40 na B, desfeita só a B,
    // voltava a valer 100 de despesa com o crédito de 60 da A ainda marcado e
    // ligado a ela — fora da receita e fora do abate, sumido das duas contas.
    //
    // O abate de uma compra não é um número que se guarda e se apaga: é o que
    // os créditos LIGADOS a ela dizem. Então o desfazer solta os créditos da
    // passada e refaz a conta de cada compra tocada com os que ficaram.
    // ------------------------------------------------------------------

    /**
     * Desfaz o que uma passada marcou: solta os créditos dela e recalcula as
     * compras a partir dos créditos que CONTINUAM ligados.
     *
     * <p>Para cada compra tocada, depois de soltar:
     * <ul>
     *   <li>o abate vira a soma dos créditos ainda ligados, com o teto do
     *       valor da compra — nunca zero por decreto;</li>
     *   <li>a marca de estorno INTEIRO só se mantém se o que ficou ligado
     *       ainda cobre a compra toda. Sem isso a compra volta a contar, pelo
     *       líquido. A marca nunca é criada aqui, só mantida ou solta.</li>
     * </ul>
     *
     * <p>Roda na transação de quem chama (o desfazer do vigia): soltar o
     * crédito sem refazer a compra é exatamente o defeito que isto conserta.
     *
     * @param ids as linhas que a passada tocou — créditos e compras
     */
    @Transactional
    default void releaseRefunds(UUID userId, Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return;
        // As compras apontadas pelos créditos têm de ser lidas ANTES de soltar
        // o vínculo: depois, não há mais como chegar nelas. A passada grava as
        // duas pontas, então elas já costumam estar em `ids`; isto cobre a
        // passada antiga que só gravou o crédito
        java.util.Set<UUID> compras = new java.util.LinkedHashSet<>(ids);
        compras.addAll(findRefundTargets(userId, ids));

        releaseRefundCredits(userId, ids);

        for (RefundState compra : findRefundStates(userId, compras)) {
            BigDecimal valor = compra.getAmount().abs();
            BigDecimal ligado = sumLinkedRefunds(userId, compra.getId());
            if (ligado == null) ligado = BigDecimal.ZERO;
            boolean aindaInteiro = compra.getRefunded() && ligado.compareTo(valor) >= 0;
            setRefundState(userId, compra.getId(), aindaInteiro, ligado.min(valor));
        }
    }

    /** As compras para as quais estes créditos apontam. */
    @Query("select distinct t.refundOfId from BankTransaction t "
            + "where t.user.id = :userId and t.id in :ids and t.refundOfId is not null")
    List<UUID> findRefundTargets(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    /**
     * Solta os CRÉDITOS: a marca e o vínculo. Só valor positivo — a compra da
     * mesma lista não é solta por aqui, é recalculada.
     */
    @Modifying
    @Transactional
    @Query("update BankTransaction t set t.refunded = false, t.refundOfId = null "
            + "where t.user.id = :userId and t.id in :ids and t.amount > 0")
    int releaseRefundCredits(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    /**
     * O estado de estorno das COMPRAS da lista. Projeção, e não entidade: o
     * que se lê aqui vem depois de um UPDATE em massa, e a entidade que já
     * estivesse no contexto devolveria o valor de antes dele.
     */
    @Query("select t.id as id, t.amount as amount, t.refunded as refunded "
            + "from BankTransaction t "
            + "where t.user.id = :userId and t.id in :ids and t.amount < 0")
    List<RefundState> findRefundStates(@Param("userId") UUID userId,
                                       @Param("ids") Collection<UUID> ids);

    /** Soma dos créditos ainda marcados e ligados a esta compra; nulo se nenhum. */
    @Query("select sum(t.amount) from BankTransaction t "
            + "where t.user.id = :userId and t.refundOfId = :compraId and t.refunded = true")
    BigDecimal sumLinkedRefunds(@Param("userId") UUID userId, @Param("compraId") UUID compraId);

    /** Grava o estado recalculado de uma compra. */
    @Modifying
    @Transactional
    @Query("update BankTransaction t set t.refunded = :refunded, t.refundedAmount = :refundedAmount "
            + "where t.user.id = :userId and t.id = :compraId")
    int setRefundState(@Param("userId") UUID userId,
                       @Param("compraId") UUID compraId,
                       @Param("refunded") boolean refunded,
                       @Param("refundedAmount") BigDecimal refundedAmount);

    interface RefundState {
        UUID getId();

        BigDecimal getAmount();

        boolean getRefunded();
    }

    /**
     * Abate um estorno PARCIAL na compra (V40). Soma em vez de atribuir: uma
     * compra pode receber dois créditos parciais em faturas diferentes.
     *
     * <p>Soma com TETO no valor da compra. A varredura já manda só o que
     * resta, mas decide com o que leu; duas passadas ao mesmo tempo leem a
     * mesma compra e cada uma manda um abate que cabe. O teto que vale nos
     * dois casos é o que está na própria escrita.
     */
    @Modifying
    @Transactional
    @Query("update BankTransaction t set t.refundedAmount = "
            + "case when t.refundedAmount + :amount > abs(t.amount) then abs(t.amount) "
            + "else t.refundedAmount + :amount end "
            + "where t.user.id = :userId and t.id = :compraId")
    int applyPartialRefund(@Param("userId") UUID userId,
                           @Param("compraId") UUID compraId,
                           @Param("amount") BigDecimal amount);

    /**
     * REIVINDICA o crédito para uma compra: marca e liga numa escrita só, e só
     * se ele ainda não tiver dono.
     *
     * <p>É a trava da varredura de estornos. Duas varreduras ao mesmo tempo (a
     * higiene e o botão) leem o mesmo crédito solto e decidem, as duas,
     * abatê-lo da compra: o estorno de 60 virava 100 abatidos. A condição
     * {@code refundOfId is null} mora no UPDATE, e não numa leitura anterior:
     * a segunda varredura espera a primeira confirmar, reavalia a condição e
     * altera ZERO linhas. Só quem alterou uma linha segue para o abate.
     *
     * <p>A condição é o vínculo, e não a marca: o crédito de cartão já chega
     * marcado e sem vínculo (o conector o grava neutro), e é justamente ele
     * que ainda procura a compra.
     *
     * @return 1 se o crédito ficou com esta varredura; 0 se já era de outra
     */
    @Modifying
    @Transactional
    @Query("update BankTransaction t set t.refunded = true, t.refundOfId = :compraId "
            + "where t.user.id = :userId and t.id = :creditoId and t.refundOfId is null")
    int claimRefund(@Param("userId") UUID userId,
                    @Param("creditoId") UUID creditoId,
                    @Param("compraId") UUID compraId);

    /**
     * Marca em bloco o lado descartado de pares duplicados (V26). O motivo viaja
     * junto porque distingue o que a varredura decidiu do que a pessoa decidiu —
     * e só o primeiro pode ser revisto por uma varredura futura.
     */
    @Modifying
    @Transactional
    @Query("update BankTransaction t set t.ignored = true, "
            + "t.ignoredReason = br.com.economize.model.BankTransaction$IgnoredReason.DUPLICATE "
            + "where t.user.id = :userId and t.id in :ids")
    int markAsIgnoredDuplicate(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    interface CategoryTotal {
        UUID getCategoryId();

        String getType();

        java.math.BigDecimal getTotal();

        long getTxCount();
    }
}
