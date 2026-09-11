package br.com.economize.service;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.analytics.DailyTotalResponse;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.wish.CycleCaveatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * EC-235 — os totais por dia que alimentam o calendário.
 *
 * <p>Os números são os do extrato REAL do dono (Inter, 10/08 a 10/09/2026):
 * 04/09 com R$ 157,80 de saída e R$ 2.813,94 de entrada, 05/09 com R$ 3.971,83
 * de saída e R$ 957,13 de entrada, 08/09 com R$ 188,54 de saída.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsDailyTotalsTest {

    private static final String EMAIL = "dono@economize.test";

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CycleCaveatService cycleCaveatService;

    private AnalyticsService service;
    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Dono").password("x").build();
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        service = new AnalyticsService(bankTransactionRepository, categoryRepository,
                userRepository, cycleCaveatService);
    }

    @Test
    @DisplayName("Os três dias reais de setembro voltam com os valores certos")
    void osTresDiasReais() {
        daJanela(
                tx("2026-09-04", "-157.80"),
                tx("2026-09-04", "2813.94"),
                tx("2026-09-05", "-3971.83"),
                tx("2026-09-05", "957.13"),
                tx("2026-09-08", "-188.54"));

        List<DailyTotalResponse> dias = service.dailyTotals(EMAIL, janela());

        assertThat(dias).hasSize(3);
        assertThat(dias.get(0).date()).isEqualTo(LocalDate.parse("2026-09-04"));
        assertThat(dias.get(0).spent()).isEqualByComparingTo("157.80");
        assertThat(dias.get(0).earned()).isEqualByComparingTo("2813.94");
        assertThat(dias.get(0).count()).isEqualTo(2);
        assertThat(dias.get(1).spent()).isEqualByComparingTo("3971.83");
        assertThat(dias.get(2).spent()).isEqualByComparingTo("188.54");
    }

    @Test
    @DisplayName("Vem em ordem de data, do mais antigo para o mais novo")
    void ordenadoPorData() {
        daJanela(
                tx("2026-09-08", "-188.54"),
                tx("2026-09-04", "-157.80"),
                tx("2026-09-05", "-3971.83"));

        assertThat(service.dailyTotals(EMAIL, janela()))
                .extracting(DailyTotalResponse::date)
                .containsExactly(LocalDate.parse("2026-09-04"),
                        LocalDate.parse("2026-09-05"),
                        LocalDate.parse("2026-09-08"));
    }

    @Test
    @DisplayName("As MESMAS exclusões das outras somas — um dia de aplicação não é dia caro")
    void asMesmasExclusoes() {
        BankTransaction aplicacao = tx("2026-09-05", "-411.35");
        aplicacao.setInternalTransfer(true);
        BankTransaction duplicata = tx("2026-09-05", "-50.00");
        duplicata.setIgnored(true);
        BankTransaction estornada = tx("2026-09-05", "-30.00");
        estornada.setRefunded(true);
        daJanela(aplicacao, duplicata, estornada, tx("2026-09-05", "-20.00"));

        List<DailyTotalResponse> dias = service.dailyTotals(EMAIL, janela());

        assertThat(dias).singleElement().satisfies(dia -> {
            assertThat(dia.spent()).isEqualByComparingTo("20.00");
            assertThat(dia.count()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("Dia SEM movimento não volta: ausência é ausência")
    void diaVazioNaoVolta() {
        // Quem monta a grade é a tela, que sabe quantos dias o mês tem;
        // mandar trinta zeros para ela redesenhar a mesma coisa é pagar por nada
        daJanela(tx("2026-09-04", "-157.80"));

        assertThat(service.dailyTotals(EMAIL, janela())).hasSize(1);
    }

    @Test
    @DisplayName("Período inteiro marcado devolve lista vazia, não estoura")
    void tudoMarcadoDevolveVazio() {
        BankTransaction marcada = tx("2026-09-05", "-411.35");
        marcada.setInternalTransfer(true);
        daJanela(marcada);

        assertThat(service.dailyTotals(EMAIL, janela())).isEmpty();
    }

    @Test
    @DisplayName("Sem lançamento nenhum, lista vazia")
    void semLancamentos() {
        daJanela();

        assertThat(service.dailyTotals(EMAIL, janela())).isEmpty();
    }

    // ------------------------------------------------------------------ apoio

    private AnalysisWindow janela() {
        return AnalysisWindow.of(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"));
    }

    private void daJanela(BankTransaction... transacoes) {
        when(bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        eq(user.getId()), any(), any()))
                .thenReturn(List.of(transacoes));
    }

    private BankTransaction tx(String data, String valor) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(user)
                .type(valor.startsWith("-") ? "DEBIT" : "CREDIT")
                .amount(new BigDecimal(valor))
                .description("lançamento")
                .date(OffsetDateTime.parse(data + "T12:00:00Z"))
                .build();
    }
}
