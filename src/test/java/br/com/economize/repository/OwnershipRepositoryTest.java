package br.com.economize.repository;

import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.PluggyItem;
import br.com.economize.model.RecurringSeries;
import br.com.economize.model.Report;
import br.com.economize.model.Transaction;
import br.com.economize.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cláusula de dono do resto do catálogo (EC-117): relatórios, contas de
 * conector, carteira, séries recorrentes e conexões do agregador.
 *
 * <p>É o mesmo risco em toda rota que recebe id na URL — responder com o
 * recurso de outra pessoa —, e nenhum teste de serviço o alcança, porque o
 * serviço confia no que o repositório devolve. O IDOR do EC-037 nasceu
 * exatamente de um {@code findById} solto.
 *
 * <p>Aqui também roda o {@code detachFromItem}: o schema de produção tem
 * {@code ON DELETE SET NULL} na coluna (V16), mas o H2 destes testes monta o
 * schema pelo mapeamento JPA, que não carrega a regra do FK. O efeito está
 * declarado na consulta justamente para não depender do DDL — e é isto que
 * prova que está.
 */
@DataJpaTest
@DisplayName("Cláusula de dono no resto do catálogo (EC-117)")
class OwnershipRepositoryTest {

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private ConnectorAccountRepository connectorAccountRepository;

    @Autowired
    private RecurringSeriesRepository recurringSeriesRepository;

    @Autowired
    private PluggyItemRepository pluggyItemRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager em;

    private User dono;
    private User estranho;

    @BeforeEach
    void setUp() {
        dono = userRepository.save(user("dono@economize.test"));
        estranho = userRepository.save(user("estranho@economize.test"));
    }

    private User user(String email) {
        return User.builder()
                .name(email)
                .email(email)
                .password("nao-importa")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private Report relatorio(User owner, Report.Period period, int mes) {
        return reportRepository.save(Report.builder()
                .user(owner)
                .period(period)
                .startDate(OffsetDateTime.of(2026, mes, 1, 0, 0, 0, 0, ZoneOffset.UTC))
                .endDate(OffsetDateTime.of(2026, mes, 28, 0, 0, 0, 0, ZoneOffset.UTC))
                .totalIncome(new BigDecimal("4400.00"))
                .totalExpense(new BigDecimal("3500.00"))
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
    }

    private ConnectorAccount conta(User owner, String nome, UUID itemId) {
        return connectorAccountRepository.save(ConnectorAccount.builder()
                .user(owner)
                .pluggyItemId(itemId)
                .providerAccountId("prov-" + UUID.randomUUID())
                .name(nome)
                .type(ConnectorAccount.AccountType.BANK)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
    }

    private RecurringSeries serie(User owner, String chave, RecurringSeries.Flow flow) {
        return recurringSeriesRepository.save(RecurringSeries.builder()
                .user(owner)
                .merchantKey(chave)
                .flow(flow)
                .cadence(RecurringSeries.Cadence.MONTHLY)
                .amountType(RecurringSeries.AmountType.FIXED)
                .expectedAmount(new BigDecimal("99.90"))
                .occurrences(3)
                .active(true)
                .dismissed(false)
                .source(RecurringSeries.Source.DETECTED)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
    }

    private Transaction operacao(User owner, String ativo) {
        return transactionRepository.save(Transaction.builder()
                .user(owner)
                .assetCode(ativo)
                .type("BUY")
                .quantity(new BigDecimal("100"))
                .priceAtTransaction(new BigDecimal("38.42"))
                .build());
    }

    private PluggyItem item(User owner, String itemId) {
        return pluggyItemRepository.save(PluggyItem.builder()
                .user(owner)
                .itemId(itemId)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
    }

    // -------------------------------------------------------- relatórios

    @Test
    @DisplayName("O relatório de outro usuário não abre por id")
    void relatorioAlheioNaoAbre() {
        Report alheio = relatorio(estranho, Report.Period.MONTHLY, 7);

        assertThat(reportRepository.findByIdAndUserId(alheio.getId(), dono.getId())).isEmpty();
    }

    @Test
    @DisplayName("A página de relatórios vem do dono e em ordem decrescente")
    void paginaDoDonoEmOrdem() {
        relatorio(dono, Report.Period.MONTHLY, 5);
        relatorio(dono, Report.Period.MONTHLY, 7);
        relatorio(estranho, Report.Period.MONTHLY, 12);

        var pagina = reportRepository.findByUserIdOrderByStartDateDesc(
                dono.getId(), PageRequest.of(0, 10));

        assertThat(pagina.getTotalElements()).isEqualTo(2);
        // Mais recente primeiro: a lista abre no relatório que interessa
        assertThat(pagina.getContent().get(0).getStartDate().getMonthValue()).isEqualTo(7);
    }

    @Test
    @DisplayName("O filtro por período não mistura semanal com mensal")
    void filtroPorPeriodo() {
        relatorio(dono, Report.Period.MONTHLY, 7);
        relatorio(dono, Report.Period.WEEKLY, 7);

        var mensais = reportRepository.findByUserIdAndPeriodOrderByStartDateDesc(
                dono.getId(), Report.Period.MONTHLY, PageRequest.of(0, 10));

        assertThat(mensais.getTotalElements()).isEqualTo(1);
        assertThat(mensais.getContent().get(0).getPeriod()).isEqualTo(Report.Period.MONTHLY);
    }

    @Test
    @DisplayName("A paginação corta pelo tamanho pedido, sem perder o total")
    void paginacaoRespeitaOTamanho() {
        relatorio(dono, Report.Period.MONTHLY, 5);
        relatorio(dono, Report.Period.MONTHLY, 6);
        relatorio(dono, Report.Period.MONTHLY, 7);

        var primeira = reportRepository.findByUserIdOrderByStartDateDesc(
                dono.getId(), PageRequest.of(0, 2));

        assertThat(primeira.getContent()).hasSize(2);
        // O total é do conjunto, não da página: a tela precisa dos dois para
        // saber que existe uma próxima
        assertThat(primeira.getTotalElements()).isEqualTo(3);
    }

    // ---------------------------------------------------- contas do conector

    @Test
    @DisplayName("A conta de outro usuário não retorna nem para confirmar que existe")
    void contaAlheiaNaoRetorna() {
        ConnectorAccount alheia = conta(estranho, "Conta dele", UUID.randomUUID());

        assertThat(connectorAccountRepository.findByIdAndUserId(alheia.getId(), dono.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("O alvo do upsert é o par (usuário, conta no provedor)")
    void upsertPorUsuarioEContaDoProvedor() {
        ConnectorAccount minha = conta(dono, "Corrente", UUID.randomUUID());

        assertThat(connectorAccountRepository.findByUserIdAndProviderAccountId(
                dono.getId(), minha.getProviderAccountId())).isPresent();
        // O id do provedor pode repetir entre usuários (contas conjuntas, o
        // mesmo banco): sem o dono na chave, uma sincronização sobrescreveria
        // a conta da outra pessoa
        assertThat(connectorAccountRepository.findByUserIdAndProviderAccountId(
                estranho.getId(), minha.getProviderAccountId())).isEmpty();
    }

    @Test
    @DisplayName("As órfãs do dono são as candidatas a readoção")
    void orfasDoDono() {
        conta(dono, "Vinculada", UUID.randomUUID());
        conta(dono, "Orfã", null);
        conta(estranho, "Orfã dele", null);

        var orfas = connectorAccountRepository.findAllByUserIdAndPluggyItemIdIsNull(dono.getId());

        assertThat(orfas).extracting(ConnectorAccount::getName).containsExactly("Orfã");
    }

    @Test
    @DisplayName("Desvincular solta as contas daquela conexão, e só daquela")
    void desvincularSoltaSoAConexaoPedida() {
        UUID conexao = UUID.randomUUID();
        UUID outraConexao = UUID.randomUUID();
        ConnectorAccount daConexao = conta(dono, "Corrente", conexao);
        ConnectorAccount deOutra = conta(dono, "Cartão", outraConexao);

        int soltas = connectorAccountRepository.detachFromItem(dono.getId(), conexao);
        em.clear();

        assertThat(soltas).isEqualTo(1);
        assertThat(connectorAccountRepository.findById(daConexao.getId())
                .orElseThrow().getPluggyItemId()).isNull();
        // A outra conexão continua vinculada: soltar tudo apagaria a origem de
        // lançamentos que continuam chegando
        assertThat(connectorAccountRepository.findById(deOutra.getId())
                .orElseThrow().getPluggyItemId()).isEqualTo(outraConexao);
    }

    @Test
    @DisplayName("Desvincular não alcança a conta de outro usuário")
    void desvincularRespeitaODono() {
        UUID conexao = UUID.randomUUID();
        ConnectorAccount alheia = conta(estranho, "Conta dele", conexao);

        int soltas = connectorAccountRepository.detachFromItem(dono.getId(), conexao);
        em.clear();

        assertThat(soltas).isZero();
        assertThat(connectorAccountRepository.findById(alheia.getId())
                .orElseThrow().getPluggyItemId()).isEqualTo(conexao);
    }

    // ------------------------------------------------- carteira de ativos

    @Test
    @DisplayName("A operação de outro usuário não abre por id")
    void operacaoAlheiaNaoAbre() {
        Transaction alheia = operacao(estranho, "PETR4");

        // O serviço buscava por id e comparava o e-mail depois, respondendo
        // 403 — e um 403 confirma que aquele id existe para quem tem qualquer
        // token. Com o dono na consulta, a rota responde 404
        assertThat(transactionRepository.findByIdAndUserId(alheia.getId(), dono.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("O dono encontra a própria operação")
    void operacaoDoDonoAbre() {
        Transaction minha = operacao(dono, "PETR4");

        assertThat(transactionRepository.findByIdAndUserId(minha.getId(), dono.getId()))
                .isPresent();
    }

    // ------------------------------------------------- séries recorrentes

    @Test
    @DisplayName("A série de outro usuário não abre por id")
    void serieAlheiaNaoAbre() {
        RecurringSeries alheia = serie(estranho, "netflix", RecurringSeries.Flow.EXPENSE);

        assertThat(recurringSeriesRepository.findByIdAndUserId(alheia.getId(), dono.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("A colisão de série é por usuário, chave e fluxo")
    void colisaoDeSeriePorUsuarioChaveFluxo() {
        serie(dono, "netflix", RecurringSeries.Flow.EXPENSE);

        assertThat(recurringSeriesRepository.findByUserIdAndMerchantKeyAndFlow(
                dono.getId(), "netflix", RecurringSeries.Flow.EXPENSE)).isPresent();
        // A mesma chave em fluxo oposto é outra série: "salário" entra como
        // receita e o estorno do mesmo estabelecimento, como despesa
        assertThat(recurringSeriesRepository.findByUserIdAndMerchantKeyAndFlow(
                dono.getId(), "netflix", RecurringSeries.Flow.INCOME)).isEmpty();
        // E a assinatura do vizinho nunca colide com a minha
        assertThat(recurringSeriesRepository.findByUserIdAndMerchantKeyAndFlow(
                estranho.getId(), "netflix", RecurringSeries.Flow.EXPENSE)).isEmpty();
    }

    @Test
    @DisplayName("A lista de séries é só do dono")
    void listaDeSeriesPorDono() {
        serie(dono, "netflix", RecurringSeries.Flow.EXPENSE);
        serie(estranho, "spotify", RecurringSeries.Flow.EXPENSE);

        assertThat(recurringSeriesRepository.findAllByUserId(dono.getId()))
                .extracting(RecurringSeries::getMerchantKey).containsExactly("netflix");
    }

    // ----------------------------------------------- conexões do agregador

    @Test
    @DisplayName("A conexão de outro usuário não abre por id nem por itemId")
    void conexaoAlheiaNaoAbre() {
        PluggyItem alheia = item(estranho, "item-abc");

        assertThat(pluggyItemRepository.findByIdAndUserId(alheia.getId(), dono.getId())).isEmpty();
        assertThat(pluggyItemRepository.findByItemIdAndUserId("item-abc", dono.getId())).isEmpty();
    }

    @Test
    @DisplayName("O itemId do provedor é único GLOBAL, e não por usuário")
    void itemIdEUnicoGlobal() {
        item(estranho, "item-abc");

        // Diferente de todo o resto daqui de propósito: o item é a conexão
        // criada no provedor, e o mesmo id em duas contas do Economize
        // significaria duas pessoas sincronizando o banco de uma delas
        assertThat(pluggyItemRepository.existsByItemId("item-abc")).isTrue();
        assertThat(pluggyItemRepository.countByUserId(dono.getId())).isZero();
        assertThat(pluggyItemRepository.countByUserId(estranho.getId())).isEqualTo(1);
    }
}
