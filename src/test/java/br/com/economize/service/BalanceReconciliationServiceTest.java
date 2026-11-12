package br.com.economize.service;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.ConnectorAccountRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.BalanceReconciliationService.Finding;
import br.com.economize.service.BalanceReconciliationService.Kind;
import br.com.economize.service.BalanceReconciliationService.Report;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * As duas fontes discordando — e o app dizendo o que exatamente está errado.
 *
 * <p>O caso que dá nome a tudo isto é o do concorrente: saldo R$ 0,00 com
 * "Atualizado agora" numa conta que teve movimento. Ele é o primeiro teste.
 *
 * <p><b>Contra banco, e não com repositório dublado.</b> A conferência passou a
 * pedir ao banco o recorte e a soma (por conta e por data) em vez de carregar o
 * extrato inteiro e filtrar em memória. Com o repositório dublado, o teste só
 * provaria que o serviço acredita no número que o dublê devolve — a regra, que
 * agora mora na consulta, não seria executada por ninguém. Este arquivo foi
 * escrito e rodado verde contra a implementação ANTIGA (a que carregava tudo)
 * e depois, sem tocar numa linha, contra a nova: é ele quem diz que o resultado
 * é o mesmo.
 *
 * <p>As assimetrias fixadas mais abaixo são as que existiam, não as que alguém
 * escolheria hoje. Estão aqui para não mudarem por acidente.
 */
@DataJpaTest(properties = {
        // Só para CONTAR o que a conferência carrega (último teste). O log por
        // sessão que a opção liga fica mudo: não é ele que interessa
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@Import(BalanceReconciliationService.class)
class BalanceReconciliationServiceTest {

    private static final String EMAIL = "dono@economize.test";
    private static final OffsetDateTime AGORA = OffsetDateTime.parse("2026-09-10T12:00:00Z");

    @Autowired
    private BalanceReconciliationService service;

    @Autowired
    private ConnectorAccountRepository accountRepository;

    @Autowired
    private BankTransactionRepository bankTransactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private User user;
    private User estranho;

    @BeforeEach
    void setUp() {
        user = userRepository.save(usuario(EMAIL));
        estranho = userRepository.save(usuario("estranho@economize.test"));
    }

    @Test
    @DisplayName("Saldo zero numa conta com movimento é leitura que falhou, não saldo")
    void zeroComMovimento() {
        ConnectorAccount conta = conta("Inter ····2750", BigDecimal.ZERO, AGORA.minusMinutes(5));
        tx(conta, "-180.00", AGORA.minusDays(2));

        Report relatorio = conferir();

        assertThat(relatorio.findings())
                .extracting(Finding::kind)
                .contains(Kind.ZERO_COM_MOVIMENTO);
        assertThat(relatorio.findings().get(0).message())
                .contains("leitura que falhou");
    }

    @Test
    @DisplayName("Saldo zero numa conta PARADA não vira aviso — poupança esquecida existe")
    void zeroSemMovimentoNaoAvisa() {
        ConnectorAccount conta = conta("Poupança parada", BigDecimal.ZERO, AGORA.minusMinutes(5));
        tx(conta, "-10.00", AGORA.minusDays(400));

        Report relatorio = conferir();

        // Alarme que toca sempre é o mesmo que alarme nenhum
        assertThat(relatorio.findings())
                .extracting(Finding::kind)
                .doesNotContain(Kind.ZERO_COM_MOVIMENTO);
    }

    @Test
    @DisplayName("Conta ligada que nunca informou saldo é dito, não escondido")
    void semSaldoInformado() {
        ConnectorAccount conta = conta("Banco sem saldo", null, null);
        tx(conta, "-50.00", AGORA.minusDays(1));

        Report relatorio = conferir();

        assertThat(relatorio.findings()).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(Kind.SEM_SALDO_INFORMADO);
            assertThat(f.accountId()).isEqualTo(conta.getId());
            assertThat(f.accountName()).isEqualTo("Banco sem saldo");
            assertThat(f.reportedBalance()).isNull();
            assertThat(f.reportedAt()).isNull();
            assertThat(f.movementAfter()).isNull();
            assertThat(f.message()).isEqualTo(
                    "A instituição não informou saldo nesta conta. O número da tela vem só "
                            + "dos lançamentos importados, sem segunda fonte para conferir.");
        });
    }

    @Test
    @DisplayName("Leitura com mais de 48 h vira aviso, com as horas na frase")
    void saldoVelho() {
        conta("Inter ····2750", new BigDecimal("1200.00"), AGORA.minusHours(60));

        Report relatorio = conferir();

        assertThat(relatorio.findings()).singleElement()
                .satisfies(f -> {
                    assertThat(f.kind()).isEqualTo(Kind.SALDO_VELHO);
                    assertThat(f.message()).isEqualTo(
                            "O saldo desta conta foi lido há 60 horas. A sincronização é "
                                    + "diária, então pelo menos uma leitura falhou sem avisar.");
                    assertThat(f.reportedBalance()).isEqualByComparingTo("1200.00");
                    assertThat(f.reportedAt()).isEqualTo(AGORA.minusHours(60));
                    assertThat(f.movementAfter()).isNull();
                });
    }

    @Test
    @DisplayName("Leitura com 48 h exatas ainda não é velha")
    void quarentaEOitoHorasExatasNaoAvisa() {
        conta("Inter ····2750", new BigDecimal("1200.00"), AGORA.minusHours(48));

        assertThat(conferir().clean()).isTrue();
    }

    @Test
    @DisplayName("Leitura recente e sem movimento posterior: nenhum aviso")
    void tudoEmOrdem() {
        ConnectorAccount conta = conta("Inter ····2750", new BigDecimal("1200.00"),
                AGORA.minusHours(2));
        tx(conta, "-50.00", AGORA.minusHours(5));

        Report relatorio = conferir();

        assertThat(relatorio.clean()).isTrue();
        assertThat(relatorio.accountsChecked()).isEqualTo(1);
    }

    @Test
    @DisplayName("Movimento depois da leitura sai com a soma exata do que falta")
    void movimentoAposLeitura() {
        ConnectorAccount conta = conta("Inter ····2750", new BigDecimal("1200.00"),
                AGORA.minusHours(6));
        tx(conta, "-180.00", AGORA.minusHours(3));
        tx(conta, "50.00", AGORA.minusHours(1));
        tx(conta, "-999.00", AGORA.minusHours(9));

        Report relatorio = conferir();

        assertThat(relatorio.findings()).singleElement()
                .satisfies(f -> {
                    assertThat(f.kind()).isEqualTo(Kind.MOVIMENTO_APOS_LEITURA);
                    // só o que veio DEPOIS: -180 + 50; o de 9 h atrás já está no saldo
                    assertThat(f.movementAfter()).isEqualByComparingTo(new BigDecimal("-130.00"));
                    assertThat(f.reportedBalance()).isEqualByComparingTo("1200.00");
                    assertThat(f.reportedAt()).isEqualTo(AGORA.minusHours(6));
                    assertThat(f.message()).isEqualTo(
                            "Entraram lançamentos depois da última leitura de saldo. O saldo "
                                    + "informado ainda não os inclui.");
                });
    }

    @Test
    @DisplayName("Treze centavos depois da leitura não viram aviso — o piso do EC-212")
    void abaixoDoPisoNaoAvisa() {
        // O caso do concorrente: peça de tela cheia para anunciar R$ 0,13
        ConnectorAccount conta = conta("Mercado Pago", new BigDecimal("0.13"),
                AGORA.minusHours(6));
        tx(conta, "0.13", AGORA.minusHours(1));

        assertThat(conferir().clean()).isTrue();
    }

    @Test
    @DisplayName("Saldo zero com movimento só de centavos não acusa leitura falha")
    void zeroComMovimentoImaterial() {
        // Rendimento de um centavo não autoriza dizer que a leitura falhou
        ConnectorAccount conta = conta("Poupança quase parada", BigDecimal.ZERO,
                AGORA.minusMinutes(5));
        tx(conta, "0.01", AGORA.minusDays(2));

        assertThat(conferir().findings())
                .extracting(Finding::kind)
                .doesNotContain(Kind.ZERO_COM_MOVIMENTO);
    }

    @Test
    @DisplayName("Linha ignorada não conta como movimento posterior")
    void ignoradaNaoConta() {
        ConnectorAccount conta = conta("Inter ····2750", new BigDecimal("1200.00"),
                AGORA.minusHours(6));
        BankTransaction duplicata = tx(conta, "-180.00", AGORA.minusHours(3));
        duplicata.setIgnored(true);
        bankTransactionRepository.save(duplicata);

        assertThat(conferir().clean()).isTrue();
    }

    @Test
    @DisplayName("Origem desvinculada não é cobrada por saldo")
    void desvinculadaNaoECobrada() {
        ConnectorAccount conta = conta("Nubank antigo", null, null);
        conta.setPluggyItemId(null);
        accountRepository.save(conta);
        tx(conta, "-180.00", AGORA.minusHours(3));

        Report relatorio = conferir();

        // Ela já avisou que parou; cobrar saldo dela seria cobrar de quem avisou
        assertThat(relatorio.clean()).isTrue();
        assertThat(relatorio.accountsChecked()).isEqualTo(1);
    }

    @Test
    @DisplayName("Sem contas, o relatório é limpo e não estoura")
    void semContas() {
        Report relatorio = conferir();

        assertThat(relatorio.clean()).isTrue();
        assertThat(relatorio.accountsChecked()).isZero();
    }

    // ------------------------------------------------------- caracterização
    //
    // O que a conferência fazia quando carregava o extrato inteiro, fixado
    // ANTES de o recorte ir para o banco. Cada linha estranha abaixo é uma
    // linha que um "where" escrito de cabeça erraria.

    @Test
    @DisplayName("Depois da leitura: fora só a ignorada; a interna, a de outra conta e a do instante exato")
    void somaAposLeituraLinhaPorLinha() {
        OffsetDateTime lidoEm = AGORA.minusHours(6);
        ConnectorAccount conta = conta("Inter ····2750", new BigDecimal("1200.00"), lidoEm);
        ConnectorAccount outra = conta("Nubank ····1111", new BigDecimal("300.00"), lidoEm);

        tx(conta, "-180.00", AGORA.minusHours(3));
        tx(conta, "50.00", AGORA.minusHours(1));
        // Antes da leitura: já está no saldo informado
        tx(conta, "-999.00", AGORA.minusHours(9));
        // No INSTANTE da leitura: "depois" é estrito, não entra
        tx(conta, "-77.00", lidoEm);
        // Um microssegundo depois já entra
        tx(conta, "-1.00", lidoEm.plusNanos(1_000));
        // Ignorada: a única marca que tira a linha desta soma
        BankTransaction ignorada = tx(conta, "-300.00", AGORA.minusHours(2));
        ignorada.setIgnored(true);
        bankTransactionRepository.save(ignorada);
        // Perna interna e par de estorno ENTRAM: mexeram no saldo da conta de
        // verdade, e é de saldo que se está falando — não de despesa
        BankTransaction interna = tx(conta, "-40.00", AGORA.minusHours(2));
        interna.setInternalTransfer(true);
        bankTransactionRepository.save(interna);
        BankTransaction estornada = tx(conta, "-25.00", AGORA.minusHours(2));
        estornada.setRefunded(true);
        // O valor é o bruto: o abate parcial (V40) não muda o que saiu da conta
        estornada.setRefundedAmount(new BigDecimal("10.00"));
        bankTransactionRepository.save(estornada);
        // De outra conta, sem conta nenhuma, e de outro usuário com a MESMA
        // conta carimbada: nenhuma é desta conta deste dono
        tx(outra, "-2.00", AGORA.minusHours(2));
        tx(user, null, "-5000.00", AGORA.minusHours(2));
        tx(estranho, conta.getId(), "-7000.00", AGORA.minusHours(2));

        Report relatorio = conferir();

        // A outra conta teve só R$ 2,00 depois da leitura: abaixo do piso, cala
        assertThat(relatorio.accountsChecked()).isEqualTo(2);
        assertThat(relatorio.findings()).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(Kind.MOVIMENTO_APOS_LEITURA);
            assertThat(f.accountId()).isEqualTo(conta.getId());
            // -180 + 50 - 1 - 40 - 25
            assertThat(f.movementAfter()).isEqualByComparingTo("-196.00");
        });
    }

    @Test
    @DisplayName("Movimento posterior que se anula não é notícia; o piso é sobre a SOMA")
    void somaQueSeAnulaNaoAvisa() {
        ConnectorAccount conta = conta("Inter ····2750", new BigDecimal("1200.00"),
                AGORA.minusHours(6));
        tx(conta, "-800.00", AGORA.minusHours(3));
        tx(conta, "797.00", AGORA.minusHours(2));

        // -3,00 no total: duas linhas grandes, soma abaixo de R$ 5,00
        assertThat(conferir().clean()).isTrue();
    }

    @Test
    @DisplayName("A soma posterior de exatos R$ 5,00 avisa: o piso é inclusivo")
    void pisoDaSomaEInclusivo() {
        ConnectorAccount conta = conta("Inter ····2750", new BigDecimal("1200.00"),
                AGORA.minusHours(6));
        tx(conta, "-5.00", AGORA.minusHours(3));

        assertThat(conferir().findings()).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(Kind.MOVIMENTO_APOS_LEITURA);
            assertThat(f.movementAfter()).isEqualByComparingTo("-5.00");
        });
    }

    @Test
    @DisplayName("Janela de movimento: 30 dias estritos, piso por LINHA, e a ignorada conta")
    void janelaDeMovimentoLinhaPorLinha() {
        OffsetDateTime lidoEm = AGORA.minusMinutes(5);
        // Os nomes ordenam o relatório: ele sai na ordem das contas
        ConnectorAccount a = conta("A fora da janela", BigDecimal.ZERO, lidoEm);
        tx(a, "-180.00", AGORA.minusDays(31));
        // Exatamente 30 dias: "depois de" é estrito, não é movimento
        tx(a, "-180.00", AGORA.minusDays(30));

        ConnectorAccount b = conta("B um segundo dentro", BigDecimal.ZERO, lidoEm);
        tx(b, "-180.00", AGORA.minusDays(30).plusSeconds(1));

        // Ignorada e interna CONTAM como movimento: a pergunta é se a conta
        // está viva, e não se a linha entra em alguma soma
        ConnectorAccount c = conta("C so ignorada", BigDecimal.ZERO, lidoEm);
        BankTransaction ignorada = tx(c, "-180.00", AGORA.minusDays(2));
        ignorada.setIgnored(true);
        bankTransactionRepository.save(ignorada);

        ConnectorAccount d = conta("D so interna", BigDecimal.ZERO, lidoEm);
        BankTransaction interna = tx(d, "250.00", AGORA.minusDays(2));
        interna.setInternalTransfer(true);
        bankTransactionRepository.save(interna);

        // O piso é por linha: três de R$ 4,99 somam quase quinze e não contam
        ConnectorAccount e = conta("E so centavos", BigDecimal.ZERO, lidoEm);
        tx(e, "-4.99", AGORA.minusDays(2));
        tx(e, "-4.99", AGORA.minusDays(3));
        tx(e, "4.99", AGORA.minusDays(4));

        // R$ 5,00 exatos já valem, e crédito vale tanto quanto débito
        ConnectorAccount f = conta("F no piso", BigDecimal.ZERO, lidoEm);
        tx(f, "5.00", AGORA.minusDays(2));

        // Movimento só de OUTRO usuário na mesma conta: não é deste dono
        ConnectorAccount g = conta("G de outro dono", BigDecimal.ZERO, lidoEm);
        tx(estranho, g.getId(), "-180.00", AGORA.minusDays(2));

        // Saldo diferente de zero: movimento recente não é assunto
        ConnectorAccount h = conta("H com saldo", new BigDecimal("10.00"), lidoEm);
        tx(h, "-180.00", AGORA.minusDays(2));

        Report relatorio = conferir();

        assertThat(relatorio.accountsChecked()).isEqualTo(8);
        assertThat(relatorio.findings())
                .extracting(Finding::accountName, Finding::kind)
                .containsExactly(
                        tuple("B um segundo dentro", Kind.ZERO_COM_MOVIMENTO),
                        tuple("C so ignorada", Kind.ZERO_COM_MOVIMENTO),
                        tuple("D so interna", Kind.ZERO_COM_MOVIMENTO),
                        tuple("F no piso", Kind.ZERO_COM_MOVIMENTO));
        assertThat(relatorio.findings().get(0)).satisfies(achado -> {
            assertThat(achado.accountId()).isEqualTo(b.getId());
            assertThat(achado.reportedBalance()).isEqualByComparingTo("0.00");
            assertThat(achado.reportedAt()).isEqualTo(lidoEm);
            assertThat(achado.movementAfter()).isNull();
            assertThat(achado.message()).isEqualTo(
                    "A instituição informou saldo R$ 0,00, mas esta conta teve movimento nos "
                            + "últimos 30 dias. Isso costuma ser leitura que falhou, não saldo.");
        });
    }

    @Test
    @DisplayName("Uma conta com os três avisos: zero, velho e posterior, nessa ordem")
    void osTresAvisosNaOrdem() {
        ConnectorAccount conta = conta("Inter ····2750", BigDecimal.ZERO, AGORA.minusHours(72));
        tx(conta, "-180.00", AGORA.minusHours(3));
        // Conta sem saldo no meio: o aviso dela é o único, e não impede os outros
        ConnectorAccount semSaldo = conta("Banco sem saldo", null, null);
        tx(semSaldo, "-50.00", AGORA.minusHours(3));

        Report relatorio = conferir();

        assertThat(relatorio.findings())
                .extracting(Finding::accountName, Finding::kind)
                .containsExactly(
                        tuple("Banco sem saldo", Kind.SEM_SALDO_INFORMADO),
                        tuple("Inter ····2750", Kind.ZERO_COM_MOVIMENTO),
                        tuple("Inter ····2750", Kind.SALDO_VELHO),
                        tuple("Inter ····2750", Kind.MOVIMENTO_APOS_LEITURA));
        assertThat(relatorio.findings().get(2).message()).contains("72 horas");
        assertThat(relatorio.findings().get(3).movementAfter()).isEqualByComparingTo("-180.00");
    }

    @Test
    @DisplayName("Saldo informado sem data de leitura: só a pergunta do zero se aplica")
    void saldoSemDataDeLeitura() {
        ConnectorAccount zerada = conta("A zerada sem data", BigDecimal.ZERO, null);
        tx(zerada, "-180.00", AGORA.minusDays(2));
        ConnectorAccount comSaldo = conta("B com saldo sem data", new BigDecimal("900.00"), null);
        tx(comSaldo, "-180.00", AGORA.minusDays(2));

        Report relatorio = conferir();

        assertThat(relatorio.findings()).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(Kind.ZERO_COM_MOVIMENTO);
            assertThat(f.accountId()).isEqualTo(zerada.getId());
            assertThat(f.reportedAt()).isNull();
        });
    }

    @Test
    @DisplayName("Conta desvinculada não é examinada nem com saldo zero e movimento")
    void desvinculadaComSaldoNaoEExaminada() {
        ConnectorAccount conta = conta("Nubank antigo", BigDecimal.ZERO, AGORA.minusHours(72));
        conta.setPluggyItemId(null);
        accountRepository.save(conta);
        tx(conta, "-180.00", AGORA.minusHours(3));

        Report relatorio = conferir();

        assertThat(relatorio.clean()).isTrue();
        assertThat(relatorio.accountsChecked()).isEqualTo(1);
    }

    @Test
    @DisplayName("Conta de outro usuário não entra no relatório deste")
    void contaDeOutroUsuario() {
        ConnectorAccount alheia = accountRepository.save(ConnectorAccount.builder()
                .user(estranho)
                .pluggyItemId(UUID.randomUUID())
                .providerAccountId("acc-alheia")
                .name("Conta do vizinho")
                .institution("Banco Teste")
                .type(ConnectorAccount.AccountType.BANK)
                .reportedBalance(BigDecimal.ZERO)
                .reportedBalanceAt(AGORA.minusHours(72))
                .build());
        tx(estranho, alheia.getId(), "-180.00", AGORA.minusHours(3));

        Report relatorio = conferir();

        assertThat(relatorio.clean()).isTrue();
        assertThat(relatorio.accountsChecked()).isZero();
    }

    // ------------------------------------------------------------ o custo

    @Test
    @DisplayName("Conferir não carrega o extrato: 2.000 lançamentos, nenhum vira objeto")
    void naoCarregaOExtrato() {
        // A conferência roda ao abrir o extrato, num servidor de 512 MB. Ela
        // carregava TODOS os lançamentos do usuário para filtrar em memória:
        // neste cenário, 2.000 entidades por abertura de tela, e mais a cada
        // mês de uso. O recorte e a soma agora são feitos pelo banco
        ConnectorAccount corrente = conta("Inter ····2750", new BigDecimal("1200.00"),
                AGORA.minusHours(6));
        ConnectorAccount zerada = conta("Poupança", BigDecimal.ZERO, AGORA.minusHours(6));
        for (int i = 0; i < 2000; i++) {
            // Dois anos de extrato, alternando as contas; os mais novos caem
            // depois da leitura
            tx(i % 2 == 0 ? corrente : zerada, "-10.00", AGORA.minusHours(1 + i * 8L));
        }
        em.flush();
        em.clear();
        Statistics estatisticas = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        estatisticas.clear();

        Report relatorio = service.checkFor(EMAIL, AGORA);

        // O resultado é o de sempre...
        assertThat(relatorio.findings())
                .extracting(Finding::accountName, Finding::kind)
                .containsExactly(
                        tuple("Inter ····2750", Kind.MOVIMENTO_APOS_LEITURA),
                        tuple("Poupança", Kind.ZERO_COM_MOVIMENTO));
        // ...o de i = 0 (1 h atrás) é o único da corrente depois da leitura
        assertThat(relatorio.findings().get(0).movementAfter()).isEqualByComparingTo("-10.00");
        // ...e nenhum lançamento foi materializado para chegar nele
        long lancamentosCarregados = estatisticas
                .getEntityStatistics(BankTransaction.class.getName()).getLoadCount();
        assertThat(lancamentosCarregados).isZero();
    }

    // ------------------------------------------------------------------ apoio

    /**
     * Grava o que está pendente e esvazia o contexto antes de conferir: o
     * serviço tem de ler do BANCO o que o teste montou, e não receber de volta
     * os mesmos objetos que o teste ainda segura.
     */
    private Report conferir() {
        em.flush();
        em.clear();
        return service.checkFor(EMAIL, AGORA);
    }

    private User usuario(String email) {
        return User.builder()
                .name(email)
                .email(email)
                .password("nao-importa")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private ConnectorAccount conta(String nome, BigDecimal saldo, OffsetDateTime lidoEm) {
        return accountRepository.save(ConnectorAccount.builder()
                .user(user)
                .pluggyItemId(UUID.randomUUID())
                .providerAccountId("acc-" + nome.hashCode())
                .name(nome)
                .institution("Banco Teste")
                .type(ConnectorAccount.AccountType.BANK)
                .reportedBalance(saldo)
                .reportedBalanceAt(lidoEm)
                .build());
    }

    private BankTransaction tx(ConnectorAccount conta, String valor, OffsetDateTime quando) {
        return tx(user, conta.getId(), valor, quando);
    }

    private BankTransaction tx(User dono, UUID contaId, String valor, OffsetDateTime quando) {
        return bankTransactionRepository.save(BankTransaction.builder()
                .user(dono)
                .transactionId("ext-" + UUID.randomUUID())
                .type(new BigDecimal(valor).signum() < 0 ? "DEBIT" : "CREDIT")
                .accountId(contaId)
                .date(quando)
                .amount(new BigDecimal(valor))
                .description("lançamento")
                .reviewStatus(BankTransaction.ReviewStatus.CONFIRMED)
                .build());
    }
}
