package br.com.economize.service;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
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
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * EC-213 e EC-217 — quantas parcelas faltam, e até quando.
 *
 * <p>O defeito do concorrente, medido: <i>"1 de 3, última em Agosto/2026"</i>
 * em setembro de 2026. A última parcela no passado e ainda faltando duas.
 *
 * <p>A série usada aqui é a REAL do dono, tirada das faturas do Nubank que
 * chegaram em 10/09/2026: <b>Mercadolivre*Bwgshop</b>, parcela 1/3 em
 * 09/08 (R$ 199,98), 2/3 em 03/09 (R$ 199,96) e 3/3 em 09/10 (R$ 199,96).
 */
@ExtendWith(MockitoExtension.class)
class InstallmentProjectionServiceTest {

    private static final String EMAIL = "dono@economize.test";
    private static final YearMonth SETEMBRO = YearMonth.of(2026, 9);

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private InstallmentProjectionService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Dono").password("x").build();
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("Parcela 1 de 3 em AGOSTO termina em OUTUBRO — o erro do concorrente, corrigido")
    void umDeTresEmAgostoTerminaEmOutubro() {
        // Só a primeira parcela conhecida, que é a situação em que o Pierre
        // errou: em setembro ele dizia que a última tinha sido em agosto
        daExtrato(parcela("2026-08-09", "-199.98", "Mercadolivre*Bwgshop - Parcela 1/3"));

        InstallmentProjectionService.Overview visao = service.overviewFor(EMAIL, SETEMBRO);

        assertThat(visao.series()).singleElement().satisfies(serie -> {
            assertThat(serie.total()).isEqualTo(3);
            assertThat(serie.remaining()).isEqualTo(2);
            assertThat(serie.lastMonth()).isEqualTo("2026-10");
            assertThat(serie.finished()).isFalse();
        });
    }

    @Test
    @DisplayName("A projeção acerta a data REAL da última parcela")
    void projecaoBateComOReal() {
        // Do dia 9 de agosto para o dia 3 de setembro vão 25 dias; do dia 3 ao
        // dia 9 de outubro, 36. Nenhum intervalo é "um mês" — mas o MÊS avança
        // exatamente um por parcela, porque é uma por fatura
        daExtrato(
                parcela("2026-08-09", "-199.98", "Mercadolivre*Bwgshop - Parcela 1/3"),
                parcela("2026-09-03", "-199.96", "Mercadolivre*Bwgshop - Parcela 2/3"));

        InstallmentProjectionService.Overview visao = service.overviewFor(EMAIL, SETEMBRO);

        // 3/3 realmente caiu em 09/10/2026
        assertThat(visao.series()).singleElement()
                .extracting(InstallmentProjectionService.Series::lastMonth)
                .isEqualTo("2026-10");
    }

    @Test
    @DisplayName("Os dois centavos: o estimador é a parcela mais RECENTE, não a primeira")
    void osDoisCentavos() {
        // A primeira carrega o arredondamento da divisão (199,98 contra
        // 199,96). Multiplicar a primeira pelo que falta erraria o total
        daExtrato(
                parcela("2026-08-09", "-199.98", "Mercadolivre*Bwgshop - Parcela 1/3"),
                parcela("2026-09-03", "-199.96", "Mercadolivre*Bwgshop - Parcela 2/3"));

        InstallmentProjectionService.Overview visao = service.overviewFor(EMAIL, SETEMBRO);

        assertThat(visao.series().get(0).installmentAmount())
                .isEqualByComparingTo(new BigDecimal("199.96"));
        assertThat(visao.remainingTotal()).isEqualByComparingTo(new BigDecimal("199.96"));
    }

    @Test
    @DisplayName("Série completa some do 'a vencer' — não falta mais nada")
    void serieCompletaNaoCobra() {
        daExtrato(
                parcela("2026-08-09", "-199.98", "Mercadolivre*Bwgshop - Parcela 1/3"),
                parcela("2026-09-03", "-199.96", "Mercadolivre*Bwgshop - Parcela 2/3"),
                parcela("2026-10-09", "-199.96", "Mercadolivre*Bwgshop - Parcela 3/3"));

        InstallmentProjectionService.Overview visao = service.overviewFor(EMAIL, SETEMBRO);

        assertThat(visao.series()).singleElement()
                .extracting(InstallmentProjectionService.Series::finished)
                .isEqualTo(true);
        assertThat(visao.openSeries()).isZero();
        assertThat(visao.remainingTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("O total a vencer soma todas as séries abertas — o número do EC-213")
    void totalAVencer() {
        daExtrato(
                parcela("2026-08-17", "-98.30", "MERCADOLIVRE MERCADOL EXTREMA - Parcela 1/3"),
                parcela("2026-08-13", "-75.37", "Shopee SHOPEE ConnectM - Parcela 1/3"),
                parcela("2026-04-15", "-103.06", "JIM COM BUIU BIKE BARUERI - Parcela 1/2"));

        InstallmentProjectionService.Overview visao = service.overviewFor(EMAIL, SETEMBRO);

        assertThat(visao.totalSeries()).isEqualTo(3);
        // 2×98,30 + 2×75,37 + 1×103,06 — mas a de abril já venceu em maio
        assertThat(visao.openSeries()).isEqualTo(2);
        assertThat(visao.remainingTotal()).isEqualByComparingTo(new BigDecimal("347.34"));
    }

    @Test
    @DisplayName("Duas compras de 3x na MESMA loja viram duas séries, não uma")
    void mesmaLojaDuasSeries() {
        // Misturá-las inventaria um parcelamento que ninguém fez
        daExtrato(
                parcela("2026-07-10", "-50.00", "Shopee SHOPEE ConnectM - Parcela 1/3"),
                parcela("2026-08-10", "-50.00", "Shopee SHOPEE ConnectM - Parcela 2/3"),
                parcela("2026-08-20", "-30.00", "Shopee SHOPEE ConnectM - Parcela 1/3"));

        InstallmentProjectionService.Overview visao = service.overviewFor(EMAIL, SETEMBRO);

        assertThat(visao.totalSeries()).isEqualTo(2);
    }

    @Test
    @DisplayName("Série que começa no meio ainda projeta o fim certo")
    void comecaNoMeio() {
        // O histórico do provedor tem 12 meses; a parcela 1 pode ser mais
        // velha que isso. Ver só a 5/10 basta para dizer que faltam cinco
        daExtrato(parcela("2026-09-05", "-120.00", "Loja Tal - Parcela 5/10"));

        InstallmentProjectionService.Overview visao = service.overviewFor(EMAIL, SETEMBRO);

        assertThat(visao.series()).singleElement().satisfies(serie -> {
            assertThat(serie.seen()).isEqualTo(1);
            assertThat(serie.remaining()).isEqualTo(5);
            assertThat(serie.lastMonth()).isEqualTo("2027-02");
            assertThat(serie.firstMonth()).isEqualTo("2026-05");
        });
    }

    @Test
    @DisplayName("Data solta NÃO vira parcela: '03/12' num extrato brasileiro é dezembro")
    void dataNaoEParcela() {
        // A guarda vem do DebtClassifier e é a razão de ele exigir palavra de
        // parcela junto do padrão N/M
        daExtrato(parcela("2026-09-05", "-80.00", "Compra no débito Padaria 03/12"));

        assertThat(service.overviewFor(EMAIL, SETEMBRO).series()).isEmpty();
    }

    @Test
    @DisplayName("Crédito não vira parcela, e linha marcada fica de fora")
    void creditoEMarcadaForaDaConta() {
        BankTransaction marcada = parcela("2026-08-09", "-199.98",
                "Mercadolivre*Bwgshop - Parcela 1/3");
        marcada.setIgnored(true);
        daExtrato(marcada, parcela("2026-08-09", "199.98", "Estorno Parcela 1/3"));

        assertThat(service.overviewFor(EMAIL, SETEMBRO).series()).isEmpty();
    }

    @Test
    @DisplayName("Financiamento longo não entra no 'a vencer' dos parcelamentos de cartão")
    void financiamentoLongoFicaDeFora() {
        // 60 meses é financiamento; somá-lo ao parcelamento de cartão
        // misturaria duas coisas que a pessoa pensa separado
        daExtrato(parcela("2026-09-05", "-1500.00", "Parcela financiamento 12/60"));

        assertThat(service.overviewFor(EMAIL, SETEMBRO).series()).isEmpty();
    }

    @Test
    @DisplayName("Sem parcelamento nenhum, a visão é zero e não estoura")
    void semParcelamento() {
        daExtrato();

        InstallmentProjectionService.Overview visao = service.overviewFor(EMAIL, SETEMBRO);

        assertThat(visao.totalSeries()).isZero();
        assertThat(visao.remainingTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------ apoio

    private void daExtrato(BankTransaction... transacoes) {
        when(bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()))
                .thenReturn(List.of(transacoes));
    }

    private BankTransaction parcela(String data, String valor, String descricao) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(user)
                .type(valor.startsWith("-") ? "DEBIT" : "CREDIT")
                .amount(new BigDecimal(valor))
                .description(descricao)
                .date(OffsetDateTime.parse(data + "T12:00:00Z"))
                .build();
    }
}
