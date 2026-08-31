package br.com.economize.service;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.analytics.DebtOverviewResponse;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DebtInsightServiceTest {

    private static final String EMAIL = "bia@economize.dev";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final AnalysisWindow JANELA =
            AnalysisWindow.ofMonth(YearMonth.of(2026, 8));

    @Mock
    private BankTransactionRepository bankTransactionRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private DebtInsightService service;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .id(USER_ID).name("Bia").email(EMAIL).password("x").build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        givenTransactions();
    }

    @Test
    void separaDividaDeConsumoComum() {
        givenTransactions(
                debito("PARCELA 07/48 FINANCIAMENTO VEICULO", "1200.00"),
                debito("SUPERMERCADO PAO DE ACUCAR", "800.00"),
                debito("EMPRESTIMO PESSOAL", "450.00"),
                debito("NETFLIX", "39.90"));

        DebtOverviewResponse resposta = service.summarize(EMAIL, JANELA);

        assertThat(resposta.totalExpense()).isEqualByComparingTo("2489.90");
        assertThat(resposta.totalDebt()).isEqualByComparingTo("1650.00");
        // 1650 / 2489,90 = 66,3%
        assertThat(resposta.shareOfExpense()).isEqualByComparingTo("66.3");
        assertThat(resposta.groups()).extracting(DebtOverviewResponse.DebtGroup::kind)
                .containsExactly("FINANCING", "LOAN");
    }

    @Test
    void osGruposSaemDoMaisPesadoParaOMaisLeve() {
        givenTransactions(
                debito("EMPRESTIMO PESSOAL", "100.00"),
                debito("PARCELA 02/24 FINANCIAMENTO", "900.00"),
                debito("JUROS ROTATIVO", "300.00"));

        assertThat(service.summarize(EMAIL, JANELA).groups())
                .extracting(DebtOverviewResponse.DebtGroup::kind)
                .containsExactly("FINANCING", "REVOLVING", "LOAN");
    }

    @Test
    void oRotativoLevantaAlarmeProprio() {
        givenTransactions(debito("PARCELAMENTO DE FATURA", "500.00"));

        // é a dívida mais cara do país; some no meio dos outros tipos se não
        // tiver sinalização própria
        assertThat(service.summarize(EMAIL, JANELA).revolvingAlert()).isTrue();
    }

    @Test
    void semRotativoNaoHaAlarme() {
        givenTransactions(debito("PARCELA 02/24 FINANCIAMENTO", "900.00"));

        assertThat(service.summarize(EMAIL, JANELA).revolvingAlert()).isFalse();
    }

    @Test
    void aParcelaTrazQuantasFaltam() {
        givenTransactions(debito("PARCELA 07/48 FINANCIAMENTO VEICULO", "1200.00"));

        DebtOverviewResponse.DebtEntry item =
                service.summarize(EMAIL, JANELA).groups().get(0).items().get(0);

        assertThat(item.installment()).isEqualTo(7);
        assertThat(item.total()).isEqualTo(48);
        // o número que diz por quanto tempo isso ainda vai pesar
        assertThat(item.remaining()).isEqualTo(41);
    }

    @Test
    void transferenciaInternaFicaDeFora() {
        BankTransaction fatura = debito("PAGAMENTO DE FATURA PARCELAMENTO", "1500.00");
        fatura.setInternalTransfer(true);
        givenTransactions(fatura, debito("SUPERMERCADO", "200.00"));

        DebtOverviewResponse resposta = service.summarize(EMAIL, JANELA);

        // pagar a fatura não é despesa: a despesa foi a compra
        assertThat(resposta.totalExpense()).isEqualByComparingTo("200.00");
        assertThat(resposta.totalDebt()).isEqualByComparingTo("0.00");
    }

    @Test
    void entradaNaoContaComoDespesa() {
        givenTransactions(
                credito("EMPRESTIMO PESSOAL LIBERACAO", "5000.00"),
                debito("SUPERMERCADO", "200.00"));

        DebtOverviewResponse resposta = service.summarize(EMAIL, JANELA);

        // a ENTRADA do empréstimo não é receita nem despesa — e somá-la ao
        // total de dívida do mês inverteria o sinal da leitura
        assertThat(resposta.totalExpense()).isEqualByComparingTo("200.00");
        assertThat(resposta.totalDebt()).isEqualByComparingTo("0.00");
    }

    @Test
    void tipoForaDoPadraoCaiNoSinalDoValor() {
        // OFX de banco pequeno traz TRNTYPE torto; o sinal é a rede
        BankTransaction torta = debito("CONSORCIO IMOVEL", "700.00");
        torta.setType("OTHER");
        torta.setAmount(new BigDecimal("-700.00"));
        givenTransactions(torta);

        assertThat(service.summarize(EMAIL, JANELA).totalDebt()).isEqualByComparingTo("700.00");
    }

    @Test
    void oApelidoDoUsuarioMandaNaClassificacao() {
        BankTransaction tx = debito("PGTO 8812349", "600.00");
        tx.setDisplayAlias("Parcela 03/36 do consórcio");
        givenTransactions(tx);

        DebtOverviewResponse resposta = service.summarize(EMAIL, JANELA);

        // é o texto que a pessoa reconhece e que a tela mostra; classificar
        // pelo código do banco perderia o que ela mesma explicou
        assertThat(resposta.groups().get(0).kind()).isEqualTo("CONSORTIUM");
        assertThat(resposta.groups().get(0).items().get(0).installment()).isEqualTo(3);
    }

    @Test
    void periodoSemDespesaNaoInventaUmaBoaNoticia() {
        DebtOverviewResponse resposta = service.summarize(EMAIL, JANELA);

        // 0/0 não é 0%: "0% do seu mês é dívida" num mês sem extrato seria uma
        // boa notícia que ninguém apurou
        assertThat(resposta.shareOfExpense()).isNull();
        assertThat(resposta.groups()).isEmpty();
        assertThat(resposta.revolvingAlert()).isFalse();
    }

    @Test
    void aListaDeExemplosTemTeto() {
        BankTransaction[] muitas = new BankTransaction[20];
        for (int i = 0; i < muitas.length; i++) {
            muitas[i] = debito("PARCELA 01/24 LOJA " + i, "100.00");
        }
        givenTransactions(muitas);

        DebtOverviewResponse.DebtGroup grupo = service.summarize(EMAIL, JANELA).groups().get(0);

        // o total continua inteiro; só a amostra é limitada
        assertThat(grupo.count()).isEqualTo(20);
        assertThat(grupo.total()).isEqualByComparingTo("2000.00");
        assertThat(grupo.items()).hasSize(12);
    }

    private void givenTransactions(BankTransaction... transactions) {
        when(bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        eq(USER_ID), any(), any()))
                .thenReturn(List.of(transactions));
    }

    private static BankTransaction debito(String description, String amount) {
        return tx(description, amount, "DEBIT");
    }

    private static BankTransaction credito(String description, String amount) {
        return tx(description, amount, "CREDIT");
    }

    private static BankTransaction tx(String description, String amount, String type) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .description(description)
                .amount(new BigDecimal(amount))
                .type(type)
                .date(OffsetDateTime.of(LocalDate.of(2026, 8, 10), LocalTime.NOON, ZoneOffset.UTC))
                .build();
    }
}
