package br.com.economize.service.connector.pluggy;

import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.PluggyItem;
import br.com.economize.model.User;
import br.com.economize.repository.PluggyItemRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.BankStatementService;
import br.com.economize.service.ConnectorAccountService;
import br.com.economize.service.recurrence.MerchantKeyExtractor;
import br.com.economize.service.statement.parser.ParsedTransaction;
import br.com.economize.service.statement.parser.StatementFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sincroniza as conexões Pluggy DO USUÁRIO autenticado (tabela pluggy_items —
 * EC-106) para dentro do MESMO pipeline do upload de extrato: motor de
 * categorização, dedup por id do Pluggy e reconciliação entre fontes — um
 * extrato importado depois do sync (ou o contrário) não duplica nada. Lê
 * contas BANK e CREDIT (cartão de crédito, absorvendo o EC-072).
 *
 * <p><b>Regime de competência no cartão.</b> A despesa do mês é a COMPRA, no dia
 * em que ela acontece. Pagar a fatura não é despesa nova — é dinheiro do
 * titular saindo de um bolso para quitar outro — e o crédito que entra no
 * cartão não é receita. As duas pernas do pagamento são marcadas como
 * {@code internalTransfer} e saem das somas de receita/despesa (a série de
 * recorrência delas nasce INTERNAL e a previsão de saldo as ignora), enquanto
 * sinal e saldo continuam corretos. Sem isso, conectar cartão + conta corrente
 * dobrava "Despesas do mês" e inventava uma receita do tamanho da fatura.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "economize.pluggy.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PluggySyncService {

    // Teto da janela de sincronização. O pipeline segura ao mesmo tempo: as
    // ParsedTransaction da janela inteira, as entidades novas, o
    // existingInWindow do BankStatementService (tudo que o usuário JÁ tem na
    // mesma janela, como entidades gerenciadas) e os dois mapas do ledger de
    // reconciliação — na prática ~3 KB de heap vivo por transação.
    //
    // Eram 1825 dias (5 anos), e a conta que os justificava usava um heap de
    // 360 MB que NUNCA existiu de verdade: o container do plano free tem 512 MB
    // no total, e metaspace + buffers da Netty + pilhas de thread comem a
    // diferença. Em 05/09 um sync de cinco conexões matou o processo (502 aos
    // 66s) e a API ficou quatro minutos fora do ar. O JAVA_OPTS passou a caber
    // no container; este teto passou a caber no JAVA_OPTS.
    //
    // 400 dias cobrem "o ano passado inteiro" com folga e continuam MAIORES do
    // que a janela que qualquer conector de Open Finance devolve (12–24 meses,
    // e na prática 12). O teto não corta caso de uso real — corta o pedido
    // absurdo, que era ?days=999999 caminhando para as 100 mil transações POR
    // CONTA do limite de páginas do PluggyClient.
    private static final int MIN_SYNC_DAYS = 1;
    private static final int MAX_SYNC_DAYS = 400;

    /**
     * Teto de linhas por sincronização.
     *
     * <p>A janela limita o TEMPO, não o volume: quem tem oito contas ativas
     * estoura o orçamento de memória dentro de uma janela perfeitamente
     * razoável. Passar daqui responde 400 com o que fazer (sincronizar em
     * janelas menores) em vez de o processo morrer levando junto a sessão de
     * todo mundo — 15 mil linhas × ~3 KB ≈ 45 MB, que cabe nos 256 MB de heap
     * ao lado do contexto do Spring.
     */
    private static final int MAX_SYNC_TRANSACTIONS = 15_000;

    private final PluggyClient pluggyClient;
    private final UserRepository userRepository;
    // De quantas em quantas horas um retrato vira "velho". Banco nao publica
    // lancamento de minuto em minuto; 6 horas pega o mesmo dia sem queimar cota.
    private static final long HORAS_ATE_PEDIR_COLETA = 6;
    // Intervalo minimo entre dois PEDIDOS de coleta para o mesmo item.
    private static final long HORAS_ENTRE_PEDIDOS = 6;

    private final PluggyItemRepository pluggyItemRepository;
    private final PluggyItemService pluggyItemService;
    private final BankStatementService bankStatementService;
    private final ConnectorAccountService accountService;
    private final CardBillService cardBillService;

    /**
     * Contrato do APK publicado: enabled/owner/configured/itemCount existem e
     * mantêm o tipo. "owner" ficou legado — com itens por usuário, toda conta
     * gerencia as próprias conexões — e responde sempre true; "configured"
     * continua significando "este usuário consegue sincronizar agora".
     */
    public Map<String, Object> status(String email) {
        User user = requireUser(email);
        // itens de env ainda não migrados contam para o dono declarado: antes
        // da primeira sync o status dele não pode regredir para "não configurado"
        long itemCount = pluggyItemRepository.countByUserId(user.getId())
                + pluggyItemService.pendingEnvItems(email);
        return Map.of(
                "enabled", true,
                "owner", true,
                "configured", pluggyClient.isConfigured() && itemCount > 0,
                "itemCount", itemCount);
    }

    public SyncResult sync(String email, int days) {
        // validação de entrada antes de qualquer I/O. Fora da faixa é 400 com o
        // limite dito: silenciosamente virar 1 dia (o Math.max de antes) fazia
        // ?days=-5 devolver "0 transações importadas" como se a janela estivesse
        // vazia, e o usuário não tinha como saber que o parâmetro foi ignorado
        if (days < MIN_SYNC_DAYS || days > MAX_SYNC_DAYS) {
            throw new IllegalArgumentException(String.format(
                    "Janela de sincronização inválida: days deve estar entre %d e %d (recebido: %d)",
                    MIN_SYNC_DAYS, MAX_SYNC_DAYS, days));
        }
        User user = requireUser(email);
        if (!pluggyClient.isConfigured()) {
            throw new IllegalArgumentException(
                    "Conector Pluggy sem credenciais — defina PLUGGY_CLIENT_ID e PLUGGY_CLIENT_SECRET");
        }

        String apiKey = pluggyClient.authenticate();
        // primeira sync do dono de PLUGGY_ITEM_IDS migra os itens de env para a
        // tabela — depois disso a tabela é a única fonte percorrida
        pluggyItemService.seedFromEnv(user, apiKey);

        List<PluggyItem> items = pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId());
        if (items.isEmpty()) {
            throw new IllegalArgumentException(
                    "Nenhuma conexão Pluggy registrada — conecte uma instituição pelo app antes de sincronizar");
        }

        // ANTES de ler qualquer coisa: conferir a saude de cada conexao e, se o
        // retrato estiver velho, PEDIR um novo. Ler de novo um retrato velho
        // devolve o mesmo retrato velho — foi assim que nove compras de cartao
        // de setembro ficaram de fora enquanto o sync respondia 200. Ver V37.
        conferirSaude(apiKey, items);

        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.minusDays(days);

        // 1ª volta: RESOLVE todas as contas antes de puxar transação nenhuma —
        // mas sem gravar nada ainda. É preciso conhecer os cartões primeiro para
        // reconhecer, na conta corrente, qual débito é a contrapartida do
        // pagamento de fatura; conhecer é ler a listagem do provedor, não
        // escrever no banco.
        List<SyncAccount> accounts = new ArrayList<>();
        for (PluggyItem item : items) {
            for (Map<String, Object> account : pluggyClient.accounts(apiKey, item.getItemId())) {
                String accountType = String.valueOf(account.get("type"));
                boolean creditCard = "CREDIT".equalsIgnoreCase(accountType);
                // INVESTMENT e afins não são extrato: ficam fora do pipeline
                if (!creditCard && !"BANK".equalsIgnoreCase(accountType)) continue;
                String providerAccountId = text(account.get("id"));
                if (providerAccountId == null) {
                    // sem id não há como pedir as transações NEM como identificar
                    // a conta entre as syncs. O String.valueOf de antes gravava a
                    // origem literal "null" e, pelo unique (user_id,
                    // provider_account_id), DUAS contas malformadas de bancos
                    // diferentes colapsavam numa fatura só
                    log.warn("Conta sem id na resposta do Pluggy — ignorada (conector=\"{}\", tipo={}). "
                                    + "Nenhum lançamento dela entra nesta sincronização.",
                            item.getConnectorName(), accountType);
                    continue;
                }
                accounts.add(new SyncAccount(providerAccountId, creditCard,
                        snapshotOf(item, account, providerAccountId, creditCard)));
            }
        }
        // cartões primeiro, de propósito: o conjunto de valores das pernas de
        // pagamento vistas dentro deles precisa estar pronto quando a conta
        // corrente for mapeada
        accounts.sort((a, b) -> Boolean.compare(b.creditCard(), a.creditCard()));

        // Créditos vistos DENTRO de cartões — pagamento de fatura e estorno. São
        // eles a contrapartida que autoriza neutralizar o débito equivalente na
        // conta corrente, UM PARA UM (ver CardCreditPool).
        CardCreditPool cardCredits = new CardCreditPool();
        List<ParsedTransaction> parsed = new ArrayList<>();
        for (SyncAccount account : accounts) {
            List<Map<String, Object>> raw = pluggyClient.transactions(apiKey, account.id(), from, to);
            // A ORIGEM (EC-113) só passa a existir DEPOIS de o extrato dela ter
            // sido lido. Gravá-la antes deixava, numa falha do Pluggy no meio da
            // volta, uma conta registrada com zero lançamentos: ela aparecia em
            // GET /accounts, abria com invoices: [] e não havia caminho nenhum
            // para o usuário removê-la. Registrar depois torna o pior caso
            // "a conta ainda não existe", que a próxima sync resolve sozinha.
            //
            // ATENÇÃO a quem for anotar @Transactional neste sync: a correção da
            // corrida em ConnectorAccountService.insert (saveAndFlush + catch de
            // DataIntegrityViolationException + re-find) SÓ funciona porque cada
            // chamada de repositório aqui abre a própria transação. Dentro de uma
            // transação única, o unique violado marca a transação para rollback e
            // o re-find já não enxerga a linha do vencedor: volta o 500 da sync
            // concorrente, e nenhum teste desta suíte avisa.
            ConnectorAccount origin = accountService.register(user, account.snapshot());
            for (Map<String, Object> tx : raw) {
                ParsedTransaction mapped = mapTransaction(tx, account.creditCard(), origin.getId(), cardCredits);
                if (mapped == null) continue;
                if (account.creditCard() && mapped.isInternalTransfer()) {
                    cardCredits.offer(mapped.getAmount());
                }
                parsed.add(mapped);
                if (parsed.size() > MAX_SYNC_TRANSACTIONS) {
                    // Aborta ANTES de o pipeline dobrar a memória (entidades +
                    // ledger): nada foi gravado ainda além das origens, e a
                    // próxima sync com janela menor resolve
                    throw new IllegalArgumentException(String.format(
                            "Volume acima do limite: mais de %d lançamentos na janela de %d dias. "
                                    + "Sincronize em janelas menores (ex.: ?days=90).",
                            MAX_SYNC_TRANSACTIONS, days));
                }
            }
        }
        log.info("Pluggy sync: {} transações na janela {}..{} em {} item(ns) para user={}",
                parsed.size(), from, to, items.size(), user.getId());
        BankStatementService.ImportResult result =
                bankStatementService.importFromConnector(user, "Meu Pluggy", StatementFormat.PLUGGY, parsed);

        // As faturas do provedor vêm DEPOIS do extrato e nunca o derrubam:
        // extrato é o produto, fatura é enriquecimento. Foi assim que o
        // investimento ficou meses sem aparecer — a lição é não acoplar o
        // opcional ao essencial.
        try {
            cardBillService.sync(user, apiKey, items);
        } catch (Exception e) {
            log.warn("Faturas do provedor não lidas nesta sincronização: {}", e.getMessage());
        }

        // o carimbo vem depois da importação: sync que falhou não conta
        OffsetDateTime now = OffsetDateTime.now();
        items.forEach(item -> item.setLastSyncedAt(now));
        pluggyItemRepository.saveAll(items);

        return new SyncResult(result, items.size());
    }

    private ParsedTransaction mapTransaction(Map<String, Object> tx, boolean creditCard,
                                             java.util.UUID accountId, CardCreditPool cardCredits) {
        Object id = tx.get("id");
        Object amountRaw = tx.get("amount");
        Object dateRaw = tx.get("date");
        if (id == null || amountRaw == null || dateRaw == null) return null;

        // Lançamento PENDENTE fica de fora. Ele é provisório: some e volta
        // efetivado com OUTRO id (e às vezes outro valor/data), e aí nem o
        // dedupe por transactionId nem a rede de reconciliação por dia+valor
        // reconhecem que é a mesma compra — o usuário ficaria com a duplicata
        // para apagar na mão. O preço é a compra do cartão só aparecer ao
        // efetivar (1–3 dias), que é o comportamento do próprio app do banco.
        // Ausência de "status" não bloqueia: conector antigo que não informa
        // entra normalmente — o risco aqui é duplicata, não vazamento.
        if ("PENDING".equalsIgnoreCase(String.valueOf(tx.get("status")))) return null;

        BigDecimal amount = new BigDecimal(String.valueOf(amountRaw));
        // Cartão de crédito inverte a convenção do Pluggy: positivo é COMPRA
        // (aumenta a dívida da fatura) e negativo é pagamento/estorno. A
        // carteira usa a mesma convenção do extrato bancário (negativo = saída),
        // então espelhamos o sinal: a compra do cartão entra como débito — e é
        // ELA a despesa do mês (regime de competência).
        if (creditCard) amount = amount.negate();
        String type;
        if (amount.signum() != 0) {
            // mesmo critério dos parsers: o sinal é a fonte confiável
            type = amount.signum() < 0 ? "DEBIT" : "CREDIT";
        } else {
            type = "DEBIT".equalsIgnoreCase(String.valueOf(tx.get("type"))) ? "DEBIT" : "CREDIT";
        }

        String description = String.valueOf(tx.getOrDefault("description", ""));

        // a descrição segue intocada de propósito: a âncora "fatura" da
        // detecção de recorrência (ADR-015) depende do texto real do banco
        return ParsedTransaction.builder()
                .externalId("PLUGGY-" + id)
                .type(type)
                .amount(amount)
                .description(description)
                .date(parseDate(String.valueOf(dateRaw)))
                .internalTransfer(isInternalTransferLeg(creditCard, amount, description, cardCredits))
                // a origem viaja com a linha até a gravação: depois dela ninguém
                // mais saberia de qual conta a transação veio (EC-113)
                .accountId(accountId)
                .build();
    }

    /**
     * Traduz a conta como a API do Pluggy a devolve para a origem do domínio.
     *
     * <p><b>Rótulo.</b> {@code marketingName} é o nome que o usuário reconhece
     * ("Ultravioleta"); {@code name} é o genérico do produto ("Conta Corrente") e
     * serve de rede. Os últimos dígitos entram porque quem tem dois cartões do
     * mesmo banco não distingue um do outro por nome nenhum.
     *
     * <p><b>Saldo informado.</b> O provedor devolve {@code balance} em toda
     * conta e nós o jogávamos fora — todo saldo do app nascia da soma dos
     * lançamentos, sem segunda fonte para discordar quando faltasse um. Agora
     * ele viaja junto (EC-196). No cartão o campo é o valor DEVIDO, não um
     * saldo; ele é guardado do mesmo jeito e quem confere sabe distinguir pelo
     * tipo da conta.
     *
     * <p><b>Fechamento e vencimento.</b> Só existem no cartão, e são lidos das
     * datas que o provedor devolve na conta ({@code balanceCloseDate} /
     * {@code balanceDueDate}) — delas guardamos apenas o DIA DO MÊS, que é o que
     * se repete a cada ciclo. O bloco é procurado em {@code creditData} e em
     * {@code creditCardMetadata}: o nome do campo varia entre versões da API, e
     * ler os dois é mais barato do que descobrir em produção que a fatura inteira
     * caiu no mês do calendário. Ausência não é erro — é o caso previsto que faz
     * a resposta da fatura declarar {@code CALENDAR_MONTH}.
     */
    private ConnectorAccountService.AccountSnapshot snapshotOf(PluggyItem item, Map<String, Object> account,
                                                               String providerAccountId, boolean creditCard) {
        Map<String, Object> creditData = creditBlock(account);
        return new ConnectorAccountService.AccountSnapshot(
                providerAccountId,
                accountLabel(account),
                item.getConnectorName(),
                creditCard ? ConnectorAccount.AccountType.CREDIT_CARD : ConnectorAccount.AccountType.BANK,
                creditCard ? dayOfMonth(creditData.get("balanceCloseDate")) : null,
                creditCard ? dayOfMonth(creditData.get("balanceDueDate")) : null,
                item.getId(),
                decimalOrNull(account.get("balance")),
                // A hora é a da leitura: o provedor não carimba o saldo, e sem
                // hora o valor não serve de prova contra nada
                OffsetDateTime.now(),
                // O limite TOTAL do cartão vem no mesmo bloco de crédito. Era
                // jogado fora enquanto o app perguntava ao usuário quanto é —
                // metade da "ideia nova" do dono (15/09) já chegava de graça.
                // `availableCreditLimit` fica de fora de propósito: é o limite
                // menos o devido, e o devido já vem no balance
                creditCard ? decimalOrNull(creditData.get("creditLimit")) : null);
    }

    private String accountLabel(Map<String, Object> account) {
        String label = text(account.get("marketingName"));
        if (label == null) label = text(account.get("name"));
        if (label == null) label = "Conta";
        String number = text(account.get("number"));
        if (number == null) return label;
        // só os últimos dígitos: o número completo da conta é dado sensível e não
        // tem por que trafegar num rótulo de tela
        String digits = number.replaceAll("\\D", "");
        if (digits.length() < 4) return label;
        return label + " ····" + digits.substring(digits.length() - 4);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> creditBlock(Map<String, Object> account) {
        for (String key : List.of("creditData", "creditCardMetadata")) {
            if (account.get(key) instanceof Map<?, ?> block && !block.isEmpty()) {
                return (Map<String, Object>) block;
            }
        }
        return Map.of();
    }

    /** Dia do mês de uma data ISO do provedor; nulo se ausente ou ilegível. */
    private Integer dayOfMonth(Object rawDate) {
        String raw = text(rawDate);
        if (raw == null || raw.length() < 10) return null;
        try {
            return LocalDate.parse(raw.substring(0, 10)).getDayOfMonth();
        } catch (Exception e) {
            // data de terceiro em formato inesperado não pode derrubar a sync
            // inteira: sem o metadado, a fatura cai no ciclo do calendário
            return null;
        }
    }

    /**
     * Número do provedor, ou nulo. Campo de terceiro chega como Double, Integer,
     * String ou ausente, e um valor ilegível não pode derrubar a sincronização
     * inteira — nulo é o estado previsto de "não informou".
     */
    private static BigDecimal decimalOrNull(Object value) {
        String raw = text(value);
        if (raw == null) return null;
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            log.warn("Saldo informado ilegível ('{}') — conta segue sem segunda fonte", raw);
            return null;
        }
    }

    private static String text(Object value) {
        if (value == null) return null;
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() || "null".equals(raw) ? null : raw;
    }

    /**
     * A linha é perna de movimentação entre contas do PRÓPRIO titular? Se for,
     * não é receita nem despesa — o dinheiro só troca de bolso, e a despesa
     * verdadeira já foi contada na compra.
     *
     * <p>Perna do CARTÃO: dinheiro ENTRANDO numa conta de cartão nunca é receita
     * do titular — ou quita a fatura, ou estorna uma compra. Não depende de
     * texto nenhum, então vale sempre.
     *
     * <p>Perna da CONTA CORRENTE: exige DOIS sinais independentes, porque aqui
     * um falso positivo APAGA uma despesa real. (1) a descrição casa com a
     * âncora "fatura", o sinal que o projeto já cura para fatura de cartão no
     * {@link MerchantKeyExtractor}; e (2) existe, nesta mesma sync, um crédito
     * AINDA NÃO CONSUMIDO de valor idêntico dentro de algum cartão do usuário —
     * a contrapartida. Sozinho, (2) confundiria qualquer débito de valor
     * coincidente e (1) pegaria "fatura" de concessionária. Sem cartão conectado
     * o conjunto vem vazio e nada é neutralizado: para quem só importa a conta
     * corrente, o pagamento da fatura continua sendo a única representação do
     * gasto do cartão e some das despesas se for neutralizado à toa.
     *
     * <p>A ordem dos dois testes importa: a âncora é pura e vem primeiro; só
     * quem passa nela CONSOME um crédito do conjunto.
     */
    private boolean isInternalTransferLeg(boolean creditCard, BigDecimal amount, String description,
                                          CardCreditPool cardCredits) {
        if (creditCard) return amount.signum() > 0;
        if (amount.signum() >= 0) return false;
        if (!"fatura".equals(MerchantKeyExtractor.extract(description).anchor())) return false;
        return cardCredits.consume(amount);
    }

    /**
     * Créditos de cartão disponíveis para neutralizar débitos, contados POR
     * VALOR e consumidos um a um.
     *
     * <p>Antes isto era um {@code Set}, que não tem multiplicidade: um único
     * pagamento de fatura de R$ 500 autorizava neutralizar TODOS os débitos de
     * R$ 500 com "fatura" no texto — duas faturas iguais no mês, um pagamento
     * duplicado devolvido, e o mês inteiro de despesas sumia em cascata a partir
     * de uma contrapartida só. Com contagem, cada crédito neutraliza no máximo
     * um débito; o excedente volta a ser despesa comum, que é o erro seguro.
     *
     * <p><b>Limitação conhecida e documentada:</b> estorno de compra também
     * entra aqui, porque no extrato ele é indistinguível de um pagamento sem
     * apelar para heurística de texto — e texto é exatamente o que este projeto
     * não aceita como sinal único (ADR-015). O estrago possível é pequeno e
     * pedido duplo: exige um débito na conta corrente com o valor EXATO do
     * estorno e com a âncora "fatura" na descrição.
     */
    static final class CardCreditPool {

        private final Map<String, Integer> available = new java.util.HashMap<>();

        void offer(BigDecimal amount) {
            available.merge(amountKey(amount), 1, Integer::sum);
        }

        boolean consume(BigDecimal amount) {
            String key = amountKey(amount);
            Integer count = available.get(key);
            if (count == null || count <= 0) return false;
            available.put(key, count - 1);
            return true;
        }
    }

    /** Chave de valor absoluto, na mesma forma que o pareamento de PIX já usa. */
    private static String amountKey(BigDecimal amount) {
        return amount.abs().stripTrailingZeros().toPlainString();
    }

    /**
     * Conta do usuário dentro de uma conexão, já resolvida mas ainda NÃO
     * gravada: o id no provedor (para pedir as transações), o que decide o
     * tratamento de sinal e a origem pronta para virar linha assim que o extrato
     * dela for lido com sucesso.
     */
    private record SyncAccount(String id, boolean creditCard,
                               ConnectorAccountService.AccountSnapshot snapshot) {
    }

    private OffsetDateTime parseDate(String raw) {
        try {
            return OffsetDateTime.parse(raw);
        } catch (Exception e) {
            // Pluggy também devolve datas curtas YYYY-MM-DD dependendo do conector
            return LocalDate.parse(raw.substring(0, 10)).atStartOfDay().atOffset(ZoneOffset.UTC);
        }
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }

    /** Resultado da importação + quantas conexões foram percorridas. */
    public record SyncResult(BankStatementService.ImportResult result, int itemsSynced) {
    }

    /**
     * Le o estado de cada conexao no provedor e pede coleta nova quando o dado
     * esta velho.
     *
     * <p><b>Por que existe.</b> Ate 17/09/2026 o sync so lia. O Pluggy guarda o
     * ultimo retrato que ELE coletou do banco; se ninguem pedir atualizacao,
     * esse retrato envelhece sem nenhum erro aparecer. Na conta do dono o
     * retrato era de 14/08: o saldo vinha, a fatura do mes saia com total
     * R$ 0,00 e nove compras entre 05/09 e 14/09 nao existiam.
     *
     * <p><b>Por que nao espera.</b> A coleta do outro lado leva de segundos a
     * minutos e depende do banco. Bloquear a requisicao HTTP por isso, num
     * container de 512 MB com uma CPU, seria trocar um dado velho por um
     * timeout. Pedimos, gravamos o estado, e o proximo sync colhe o resultado.
     *
     * <p><b>Por que so a cada 6 horas.</b> Cada atualizacao e cobrada pelo
     * provedor e o banco do outro lado tem limite proprio. Pedir a cada toque
     * de "sincronizar" queimaria cota sem trazer nada — bancos nao publicam
     * lancamento de minuto em minuto.
     *
     * <p>Falha aqui NAO derruba a sincronizacao: dado velho e melhor que tela
     * vazia, desde que o app possa dizer que ele e velho — e agora pode.
     */
    private void conferirSaude(String apiKey, List<PluggyItem> items) {
        OffsetDateTime agora = OffsetDateTime.now();
        for (PluggyItem item : items) {
            try {
                Map<String, Object> detalhe = pluggyClient.item(apiKey, item.getItemId());
                if (detalhe == null) {
                    // o item sumiu do provedor: consentimento revogado la fora
                    item.setStatus("NOT_FOUND");
                    item.setStatusDetail("A conexao nao existe mais no provedor. E preciso conectar de novo.");
                    continue;
                }
                aplicarEstado(item, detalhe);

                boolean velho = item.horasDesdeAColeta().orElse(Long.MAX_VALUE) >= HORAS_ATE_PEDIR_COLETA;
                boolean pedidoRecente = item.getUpdateRequestedAt() != null
                        && Duration.between(item.getUpdateRequestedAt(), agora).toHours() < HORAS_ENTRE_PEDIDOS;
                if (velho && !pedidoRecente) {
                    item.setUpdateRequestedAt(agora);
                    Map<String, Object> apos = pluggyClient.requestUpdate(apiKey, item.getItemId());
                    if (apos != null) {
                        aplicarEstado(item, apos);
                    } else {
                        // recusa do provedor: so a pessoa resolve
                        item.setStatus("NEEDS_USER_ACTION");
                        item.setStatusDetail("O provedor nao consegue atualizar sozinho. "
                                + "Reconecte esta instituicao pelo app.");
                    }
                }
            } catch (RuntimeException e) {
                // conferir saude e melhoria, nao pre-requisito: se falhar, o
                // sync segue com o que der para ler
                log.warn("Nao consegui conferir a saude de uma conexao ({}); a sincronizacao segue.",
                        e.getClass().getSimpleName());
            }
        }
    }

    /** Copia o estado declarado pelo provedor para o nosso registro. */
    private void aplicarEstado(PluggyItem item, Map<String, Object> detalhe) {
        item.setStatus(text(detalhe.get("status")));
        item.setExecutionStatus(text(detalhe.get("executionStatus")));
        Object erro = detalhe.get("error");
        if (erro instanceof Map<?, ?> mapa) {
            Object msg = mapa.get("message");
            item.setStatusDetail(msg == null ? null : String.valueOf(msg).substring(0,
                    Math.min(400, String.valueOf(msg).length())));
        }
        // `lastUpdatedAt` e QUANDO O PLUGGY LEU O BANCO — a idade real do dado.
        // Nao confundir com o nosso last_synced_at, que e quando nos lemos ele.
        String coletadoEm = text(detalhe.get("lastUpdatedAt"));
        if (coletadoEm != null) {
            try {
                item.setProviderUpdatedAt(OffsetDateTime.parse(coletadoEm));
            } catch (java.time.format.DateTimeParseException ignorada) {
                // formato de terceiro: nao derrubar o sync por causa dele
            }
        }
    }

}
