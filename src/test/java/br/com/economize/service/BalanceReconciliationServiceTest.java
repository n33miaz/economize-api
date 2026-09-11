package br.com.economize.service;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.ConnectorAccountRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * As duas fontes discordando — e o app dizendo o que exatamente está errado.
 *
 * <p>O caso que dá nome a tudo isto é o do concorrente: saldo R$ 0,00 com
 * "Atualizado agora" numa conta que teve movimento. Ele é o primeiro teste.
 */
@ExtendWith(MockitoExtension.class)
class BalanceReconciliationServiceTest {

    private static final String EMAIL = "dono@economize.test";
    private static final OffsetDateTime AGORA = OffsetDateTime.parse("2026-09-10T12:00:00Z");

    @Mock
    private ConnectorAccountRepository accountRepository;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private BalanceReconciliationService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Dono").password("x").build();
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("Saldo zero numa conta com movimento é leitura que falhou, não saldo")
    void zeroComMovimento() {
        ConnectorAccount conta = conta("Inter ····2750", BigDecimal.ZERO, AGORA.minusMinutes(5));
        daConta(conta, tx(conta, "-180.00", AGORA.minusDays(2)));

        BalanceReconciliationService.Report relatorio = service.checkFor(EMAIL, AGORA);

        assertThat(relatorio.findings())
                .extracting(BalanceReconciliationService.Finding::kind)
                .contains(BalanceReconciliationService.Kind.ZERO_COM_MOVIMENTO);
        assertThat(relatorio.findings().get(0).message())
                .contains("leitura que falhou");
    }

    @Test
    @DisplayName("Saldo zero numa conta PARADA não vira aviso — poupança esquecida existe")
    void zeroSemMovimentoNaoAvisa() {
        ConnectorAccount conta = conta("Poupança parada", BigDecimal.ZERO, AGORA.minusMinutes(5));
        daConta(conta, tx(conta, "-10.00", AGORA.minusDays(400)));

        BalanceReconciliationService.Report relatorio = service.checkFor(EMAIL, AGORA);

        // Alarme que toca sempre é o mesmo que alarme nenhum
        assertThat(relatorio.findings())
                .extracting(BalanceReconciliationService.Finding::kind)
                .doesNotContain(BalanceReconciliationService.Kind.ZERO_COM_MOVIMENTO);
    }

    @Test
    @DisplayName("Conta ligada que nunca informou saldo é dito, não escondido")
    void semSaldoInformado() {
        ConnectorAccount conta = conta("Banco sem saldo", null, null);
        daConta(conta, tx(conta, "-50.00", AGORA.minusDays(1)));

        BalanceReconciliationService.Report relatorio = service.checkFor(EMAIL, AGORA);

        assertThat(relatorio.findings()).singleElement()
                .extracting(BalanceReconciliationService.Finding::kind)
                .isEqualTo(BalanceReconciliationService.Kind.SEM_SALDO_INFORMADO);
    }

    @Test
    @DisplayName("Leitura com mais de 48 h vira aviso, com as horas na frase")
    void saldoVelho() {
        ConnectorAccount conta = conta("Inter ····2750", new BigDecimal("1200.00"),
                AGORA.minusHours(60));
        daConta(conta);

        BalanceReconciliationService.Report relatorio = service.checkFor(EMAIL, AGORA);

        assertThat(relatorio.findings()).singleElement()
                .satisfies(f -> {
                    assertThat(f.kind()).isEqualTo(BalanceReconciliationService.Kind.SALDO_VELHO);
                    assertThat(f.message()).contains("60 horas");
                });
    }

    @Test
    @DisplayName("Leitura recente e sem movimento posterior: nenhum aviso")
    void tudoEmOrdem() {
        ConnectorAccount conta = conta("Inter ····2750", new BigDecimal("1200.00"),
                AGORA.minusHours(2));
        daConta(conta, tx(conta, "-50.00", AGORA.minusHours(5)));

        BalanceReconciliationService.Report relatorio = service.checkFor(EMAIL, AGORA);

        assertThat(relatorio.clean()).isTrue();
        assertThat(relatorio.accountsChecked()).isEqualTo(1);
    }

    @Test
    @DisplayName("Movimento depois da leitura sai com a soma exata do que falta")
    void movimentoAposLeitura() {
        ConnectorAccount conta = conta("Inter ····2750", new BigDecimal("1200.00"),
                AGORA.minusHours(6));
        daConta(conta,
                tx(conta, "-180.00", AGORA.minusHours(3)),
                tx(conta, "50.00", AGORA.minusHours(1)),
                tx(conta, "-999.00", AGORA.minusHours(9)));

        BalanceReconciliationService.Report relatorio = service.checkFor(EMAIL, AGORA);

        assertThat(relatorio.findings()).singleElement()
                .satisfies(f -> {
                    assertThat(f.kind())
                            .isEqualTo(BalanceReconciliationService.Kind.MOVIMENTO_APOS_LEITURA);
                    // só o que veio DEPOIS: -180 + 50; o de 9 h atrás já está no saldo
                    assertThat(f.movementAfter()).isEqualByComparingTo(new BigDecimal("-130.00"));
                });
    }

    @Test
    @DisplayName("Treze centavos depois da leitura não viram aviso — o piso do EC-212")
    void abaixoDoPisoNaoAvisa() {
        // O caso do concorrente: peça de tela cheia para anunciar R$ 0,13
        ConnectorAccount conta = conta("Mercado Pago", new BigDecimal("0.13"),
                AGORA.minusHours(6));
        daConta(conta, tx(conta, "0.13", AGORA.minusHours(1)));

        assertThat(service.checkFor(EMAIL, AGORA).clean()).isTrue();
    }

    @Test
    @DisplayName("Saldo zero com movimento só de centavos não acusa leitura falha")
    void zeroComMovimentoImaterial() {
        // Rendimento de um centavo não autoriza dizer que a leitura falhou
        ConnectorAccount conta = conta("Poupança quase parada", BigDecimal.ZERO,
                AGORA.minusMinutes(5));
        daConta(conta, tx(conta, "0.01", AGORA.minusDays(2)));

        assertThat(service.checkFor(EMAIL, AGORA).findings())
                .extracting(BalanceReconciliationService.Finding::kind)
                .doesNotContain(BalanceReconciliationService.Kind.ZERO_COM_MOVIMENTO);
    }

    @Test
    @DisplayName("Linha ignorada não conta como movimento posterior")
    void ignoradaNaoConta() {
        ConnectorAccount conta = conta("Inter ····2750", new BigDecimal("1200.00"),
                AGORA.minusHours(6));
        BankTransaction duplicata = tx(conta, "-180.00", AGORA.minusHours(3));
        duplicata.setIgnored(true);
        daConta(conta, duplicata);

        assertThat(service.checkFor(EMAIL, AGORA).clean()).isTrue();
    }

    @Test
    @DisplayName("Origem desvinculada não é cobrada por saldo")
    void desvinculadaNaoECobrada() {
        ConnectorAccount conta = conta("Nubank antigo", null, null);
        conta.setPluggyItemId(null);
        when(accountRepository.findAllByUserIdOrderByNameAsc(user.getId()))
                .thenReturn(List.of(conta));
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of());

        BalanceReconciliationService.Report relatorio = service.checkFor(EMAIL, AGORA);

        // Ela já avisou que parou; cobrar saldo dela seria cobrar de quem avisou
        assertThat(relatorio.clean()).isTrue();
        assertThat(relatorio.accountsChecked()).isEqualTo(1);
    }

    @Test
    @DisplayName("Sem contas, o relatório é limpo e não estoura")
    void semContas() {
        when(accountRepository.findAllByUserIdOrderByNameAsc(user.getId())).thenReturn(List.of());
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of());

        assertThat(service.checkFor(EMAIL, AGORA).clean()).isTrue();
    }

    // ------------------------------------------------------------------ apoio

    private ConnectorAccount conta(String nome, BigDecimal saldo, OffsetDateTime lidoEm) {
        return ConnectorAccount.builder()
                .id(UUID.randomUUID())
                .user(user)
                .pluggyItemId(UUID.randomUUID())
                .providerAccountId("acc-" + nome.hashCode())
                .name(nome)
                .institution("Banco Teste")
                .type(ConnectorAccount.AccountType.BANK)
                .reportedBalance(saldo)
                .reportedBalanceAt(lidoEm)
                .build();
    }

    private void daConta(ConnectorAccount conta, BankTransaction... transacoes) {
        when(accountRepository.findAllByUserIdOrderByNameAsc(user.getId()))
                .thenReturn(List.of(conta));
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(transacoes));
    }

    private BankTransaction tx(ConnectorAccount conta, String valor, OffsetDateTime quando) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(user)
                .accountId(conta.getId())
                .date(quando)
                .amount(new BigDecimal(valor))
                .description("lançamento")
                .build();
    }
}
