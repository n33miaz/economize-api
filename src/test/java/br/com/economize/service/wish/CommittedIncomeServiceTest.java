package br.com.economize.service.wish;

import br.com.economize.dto.wish.WishResponses;
import br.com.economize.model.IncomeSource;
import br.com.economize.repository.IncomeSourceRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.recurrence.RecurringSeriesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** "Quando o salário cair, quanto já tem dono" — com hoje fixo em 31/08/2026. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommittedIncomeServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDate HOJE = LocalDate.of(2026, 8, 31);

    @Mock
    private IncomeSourceRepository incomeSourceRepository;
    @Mock
    private RecurringSeriesService recurringSeriesService;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CommittedIncomeService service;

    @BeforeEach
    void setUp() {
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER)).thenReturn(List.of());
        when(recurringSeriesService.upcomingExpenses(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void semSalarioCadastradoAindaMostraAsContasDosProximosTrintaDias() {
        when(recurringSeriesService.upcomingExpenses(eq(USER), any(), any()))
                .thenReturn(List.of(due("Internet", "120.00", LocalDate.of(2026, 9, 10))));

        WishResponses.CommittedOverview overview = service.overview(USER, HOJE);

        // Sem âncora não dá para dizer "quando cair" — mas a lista continua útil
        assertThat(overview.salaryKnown()).isFalse();
        assertThat(overview.salaryDate()).isNull();
        assertThat(overview.free()).isNull();
        assertThat(overview.beforeSalary()).hasSize(1);
        assertThat(overview.committedBeforeSalary()).isEqualByComparingTo("120.00");
    }

    @Test
    void separaOQueVenceAntesDoSalarioDoQueSaiDepois() {
        givenSalary("4400", (short) 5);
        // antes: 31/08 a 04/09 · depois: 05/09 a 04/10
        when(recurringSeriesService.upcomingExpenses(eq(USER),
                eq(LocalDate.of(2026, 8, 31)), eq(LocalDate.of(2026, 9, 4))))
                .thenReturn(List.of(due("Streaming", "39.90", LocalDate.of(2026, 9, 2))));
        when(recurringSeriesService.upcomingExpenses(eq(USER),
                eq(LocalDate.of(2026, 9, 5)), eq(LocalDate.of(2026, 10, 4))))
                .thenReturn(List.of(
                        due("Aluguel", "1800.00", LocalDate.of(2026, 9, 10)),
                        due("Luz", "210.00", LocalDate.of(2026, 9, 15))));

        WishResponses.CommittedOverview overview = service.overview(USER, HOJE);

        assertThat(overview.salaryKnown()).isTrue();
        assertThat(overview.salaryDate()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(overview.daysUntilSalary()).isEqualTo(5);
        assertThat(overview.committedBeforeSalary()).isEqualByComparingTo("39.90");
        assertThat(overview.committedAfterSalary()).isEqualByComparingTo("2010.00");
        // é ISTO que a pessoa quer saber: do que vem, quanto é dela
        assertThat(overview.free()).isEqualByComparingTo("2390.00");
    }

    @Test
    void aJanelaDoSalarioVaiAteAVesperaDoProximo() {
        givenSalary("4400", (short) 5);

        service.overview(USER, HOJE);

        ArgumentCaptor<LocalDate> inicio = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> fim = ArgumentCaptor.forClass(LocalDate.class);
        verify(recurringSeriesService, org.mockito.Mockito.atLeastOnce())
                .upcomingExpenses(eq(USER), inicio.capture(), fim.capture());

        // o ciclo que o pagamento abre: 05/09 → 04/10, nunca 05/09 → 05/10
        assertThat(inicio.getAllValues()).contains(LocalDate.of(2026, 9, 5));
        assertThat(fim.getAllValues()).contains(LocalDate.of(2026, 10, 4));
    }

    @Test
    void noDiaDoPagamentoOSalarioCaiHojeENaoDaquiATrintaDias() {
        givenSalary("4400", (short) 5);

        WishResponses.CommittedOverview overview = service.overview(USER, LocalDate.of(2026, 9, 5));

        assertThat(overview.salaryDate()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(overview.daysUntilSalary()).isZero();
        // não existe "antes do salário" quando ele é hoje
        assertThat(overview.beforeSalary()).isEmpty();
    }

    @Test
    void ancoraDia31CaiNoUltimoDiaDoMesCurto() {
        givenSalary("4400", (short) 31);

        WishResponses.CommittedOverview overview = service.overview(USER, LocalDate.of(2027, 2, 10));

        // 31/02 não existe: o pagamento é no último dia de fevereiro
        assertThat(overview.salaryDate()).isEqualTo(LocalDate.of(2027, 2, 28));
    }

    @Test
    void salarioNaoConfirmadoNaoVirouAncora() {
        IncomeSource sugerido = source(IncomeSource.Kind.SALARY, "4400", (short) 5);
        sugerido.setConfirmed(false);
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER)).thenReturn(List.of(sugerido));

        // Enquanto ninguém confirma, o app não afirma quando o dinheiro cai
        assertThat(service.overview(USER, HOJE).salaryKnown()).isFalse();
    }

    @Test
    void oVrNaoDefineOCicloDoSalario() {
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER)).thenReturn(List.of(
                source(IncomeSource.Kind.SALARY, "4400", (short) 5),
                source(IncomeSource.Kind.MEAL_VOUCHER, "800", (short) 25)));

        WishResponses.CommittedOverview overview = service.overview(USER, HOJE);

        assertThat(overview.salaryDate()).isEqualTo(LocalDate.of(2026, 9, 5));
        // o VR tem calendário próprio, mas não paga aluguel — e não entra aqui
        assertThat(overview.expectedSalary()).isEqualByComparingTo("4400");
    }

    @Test
    void comDoisSalariosMandaOQueChegaPrimeiro() {
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER)).thenReturn(List.of(
                source(IncomeSource.Kind.SALARY, "3000", (short) 20),
                source(IncomeSource.Kind.SALARY, "1500", (short) 5)));

        WishResponses.CommittedOverview overview = service.overview(USER, HOJE);

        assertThat(overview.salaryDate()).isEqualTo(LocalDate.of(2026, 9, 5));
        // os dois entram no valor esperado; só a DATA é a do mais próximo
        assertThat(overview.expectedSalary()).isEqualByComparingTo("4500");
    }

    @Test
    void salarioComAncoraMasSemValorNaoInventaOQueSobra() {
        IncomeSource semValor = source(IncomeSource.Kind.SALARY, null, (short) 5);
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER)).thenReturn(List.of(semValor));
        when(recurringSeriesService.upcomingExpenses(eq(USER), any(), any()))
                .thenReturn(List.of(due("Aluguel", "1800.00", LocalDate.of(2026, 9, 10))));

        WishResponses.CommittedOverview overview = service.overview(USER, HOJE);

        assertThat(overview.salaryKnown()).isTrue();
        assertThat(overview.expectedSalary()).isNull();
        // sem saber quanto entra, "o que sobra" não tem resposta honesta
        assertThat(overview.free()).isNull();
        assertThat(overview.committedAfterSalary()).isEqualByComparingTo("1800.00");
    }

    @Test
    void contaDeConsumoChegaMarcadaComoEstimativa() {
        givenSalary("4400", (short) 5);
        when(recurringSeriesService.upcomingExpenses(eq(USER),
                eq(LocalDate.of(2026, 9, 5)), eq(LocalDate.of(2026, 10, 4))))
                .thenReturn(List.of(new RecurringSeriesService.UpcomingDue(
                        UUID.randomUUID(), "Luz", null, LocalDate.of(2026, 9, 15),
                        new BigDecimal("210.00"), true)));

        // a tela precisa dizer que é média do histórico, senão o total parece
        // mais exato do que é
        assertThat(service.overview(USER, HOJE).afterSalary().get(0).estimated()).isTrue();
    }

    private void givenSalary(String amount, short anchorDay) {
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER))
                .thenReturn(List.of(source(IncomeSource.Kind.SALARY, amount, anchorDay)));
    }

    private static RecurringSeriesService.UpcomingDue due(String name, String amount, LocalDate date) {
        return new RecurringSeriesService.UpcomingDue(
                UUID.randomUUID(), name, null, date, new BigDecimal(amount), false);
    }

    private static IncomeSource source(IncomeSource.Kind kind, String amount, Short anchorDay) {
        return IncomeSource.builder()
                .id(UUID.randomUUID())
                .kind(kind)
                .name(kind.name())
                .expectedAmount(amount != null ? new BigDecimal(amount) : null)
                .anchorDay(anchorDay)
                .confirmed(true)
                .active(true)
                .build();
    }
}
