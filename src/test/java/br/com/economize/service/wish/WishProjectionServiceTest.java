package br.com.economize.service.wish;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.model.IncomeSource;
import br.com.economize.model.Wish;
import br.com.economize.model.WorkProfile;
import br.com.economize.repository.IncomeSourceRepository;
import br.com.economize.repository.WorkProfileRepository;
import br.com.economize.service.AnalyticsService;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A conta que dá sentido aos Desejos. "Hoje" é fixo para o teste não mudar de
 * resultado conforme o dia em que roda.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WishProjectionServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);

    @Mock
    private IncomeSourceRepository incomeSourceRepository;
    @Mock
    private WorkProfileRepository workProfileRepository;
    @Mock
    private AnalyticsService analyticsService;

    @InjectMocks
    private WishProjectionService service;

    @BeforeEach
    void setUp() {
        // Padrão do cenário: sem fonte, sem jornada, sem histórico. Cada teste
        // liga só o que precisa — assim o que falta fica explícito na leitura
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER)).thenReturn(List.of());
        when(workProfileRepository.findById(USER)).thenReturn(Optional.empty());
        when(analyticsService.netFor(eq(USER), any())).thenReturn(net(0, 0));
    }

    // ---------------------------------------------------------------- lacunas

    @Test
    void semJornadaNaoInventaValorDaHora() {
        givenSalary("4400");

        WishBaseline baseline = service.baselineFor(USER, TODAY);

        assertThat(baseline.hourlyRate()).isNull();
        assertThat(baseline.gaps()).contains(WishBaseline.GAP_WORK_PROFILE);

        WishProjection projection = service.project(wish("18000"), baseline, TODAY);
        // O ponto do teste: nulo, e não zero. "Custa 0 horas" seria uma mentira
        // convincente, e a tela não teria como saber que precisa perguntar
        assertThat(projection.hoursOfWork()).isNull();
        assertThat(projection.workDays()).isNull();
    }

    @Test
    void semRendaConfirmadaNaoCalculaHora() {
        givenWorkProfile(5, "8");

        WishBaseline baseline = service.baselineFor(USER, TODAY);

        assertThat(baseline.hourlyRate()).isNull();
        assertThat(baseline.gaps()).contains(WishBaseline.GAP_CONFIRMED_INCOME);
    }

    @Test
    void rendaSugeridaMasNaoConfirmadaNaoEntraNaConta() {
        IncomeSource sugerida = source(IncomeSource.Kind.SALARY, "4400", null);
        sugerida.setConfirmed(false);
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER)).thenReturn(List.of(sugerida));
        givenWorkProfile(5, "8");

        WishBaseline baseline = service.baselineFor(USER, TODAY);

        // Confirmar quanto se ganha é decisão de quem ganha: até lá, o valor da
        // hora de vida da pessoa não pode ser calculado sobre um palpite
        assertThat(baseline.workIncome()).isEqualByComparingTo("0");
        assertThat(baseline.hourlyRate()).isNull();
        assertThat(baseline.gaps()).contains(WishBaseline.GAP_CONFIRMED_INCOME);
    }

    @Test
    void valeRefeicaoNaoEntraNoValorDaHora() {
        IncomeSource salario = source(IncomeSource.Kind.SALARY, "4400", null);
        IncomeSource vr = source(IncomeSource.Kind.MEAL_VOUCHER, "800", null);
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER))
                .thenReturn(List.of(salario, vr));
        givenWorkProfile(5, "8");

        WishBaseline baseline = service.baselineFor(USER, TODAY);

        // Ninguém compra uma moto com VR. Somar os 800 baratearia o desejo em
        // horas e daria uma resposta que a realidade não honra
        assertThat(baseline.workIncome()).isEqualByComparingTo("4400");
    }

    @Test
    void adiantamentoEntraPorqueEDinheiroLivre() {
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER)).thenReturn(List.of(
                source(IncomeSource.Kind.SALARY, "4400", null),
                source(IncomeSource.Kind.ADVANCE, "600", null)));
        givenWorkProfile(5, "8");

        assertThat(service.baselineFor(USER, TODAY).workIncome()).isEqualByComparingTo("5000");
    }

    // ------------------------------------------------------- o custo em horas

    @Test
    void traduzPrecoEmHorasDeTrabalho() {
        givenSalary("4400");
        givenWorkProfile(5, "8");

        WishBaseline baseline = service.baselineFor(USER, TODAY);
        // 8h x 5 dias x 52/12 semanas = 173,33h por mês
        assertThat(baseline.hoursPerMonth()).isEqualByComparingTo("173.33");
        assertThat(baseline.hourlyRate()).isEqualByComparingTo("25.39");

        WishProjection projection = service.project(wish("18000"), baseline, TODAY);
        // 18.000 x 173,33 / 4.400 — dividido pelo salário CHEIO, não pela hora
        // arredondada, para o desejo caro não deslizar horas inteiras
        assertThat(projection.hoursOfWork()).isEqualByComparingTo("709.1");
        assertThat(projection.workDays()).isEqualByComparingTo("88.6");
    }

    @Test
    void oQueFaltaEDescontadoDoQueJaFoiGuardado() {
        givenSalary("4400");
        givenWorkProfile(5, "8");

        Wish wish = wish("18000");
        wish.setSavedAmount(new BigDecimal("6000"));

        WishProjection projection = service.project(wish, service.baselineFor(USER, TODAY), TODAY);

        assertThat(projection.remaining()).isEqualByComparingTo("12000");
        // as horas seguem o que FALTA, não o preço de vitrine
        assertThat(projection.hoursOfWork()).isEqualByComparingTo("472.7");
    }

    @Test
    void desejoJaAlcancadoNaoPedeMaisNada() {
        givenSalary("4400");
        givenWorkProfile(5, "8");
        givenCycles(net(5000, 3000));

        Wish wish = wish("1000");
        wish.setSavedAmount(new BigDecimal("1000"));

        WishProjection projection = service.project(wish, service.baselineFor(USER, TODAY), TODAY);

        assertThat(projection.achieved()).isTrue();
        assertThat(projection.remaining()).isEqualByComparingTo("0");
        assertThat(projection.monthsToAfford()).isNull();
        assertThat(projection.whatIfs()).isEmpty();
    }

    // ------------------------------------------------------------- a sobra

    @Test
    void aSobraEMedianaParaOMesAtipicoNaoMandarNoResultado() {
        givenSalary("5000");
        givenWorkProfile(5, "8");
        // cinco meses normais e um desastre (o dentista): a média cairia para
        // ~583, a mediana segura em 800 — que é o que o mês típico permite
        givenCycles(net(5000, 4200), net(5000, 4200), net(5000, 4200),
                net(5000, 4200), net(5000, 4200), net(5000, 9000));

        WishBaseline baseline = service.baselineFor(USER, TODAY);

        assertThat(baseline.cyclesConsidered()).isEqualTo(6);
        assertThat(baseline.monthlyLeftover()).isEqualByComparingTo("800.00");
    }

    @Test
    void cicloSemExtratoNaoContaComoMesQueSobrouTudo() {
        givenSalary("5000");
        givenWorkProfile(5, "8");
        // três ciclos com dados, três sem extrato importado
        givenCycles(net(5000, 4000), net(5000, 4000), net(5000, 4000),
                net(0, 0), net(0, 0), net(0, 0));

        WishBaseline baseline = service.baselineFor(USER, TODAY);

        assertThat(baseline.cyclesConsidered()).isEqualTo(3);
        assertThat(baseline.monthlyLeftover()).isEqualByComparingTo("1000.00");
    }

    @Test
    void semNenhumCicloComDadosAvisaQueFaltaHistorico() {
        givenSalary("5000");
        givenWorkProfile(5, "8");

        WishBaseline baseline = service.baselineFor(USER, TODAY);

        assertThat(baseline.gaps()).contains(WishBaseline.GAP_HISTORY);
        assertThat(baseline.monthlyLeftover()).isNull();
        assertThat(service.project(wish("18000"), baseline, TODAY).monthsToAfford()).isNull();
    }

    @Test
    void medianaDeContagemParEAMediaDosDoisCentrais() {
        assertThat(WishProjectionService.median(List.of(
                new BigDecimal("100"), new BigDecimal("300"),
                new BigDecimal("200"), new BigDecimal("400"))))
                .isEqualByComparingTo("250.00");
    }

    // ------------------------------------------------- prazo e parcelamento

    @Test
    void daOPrazoEAsParcelasPelaSobraNaoPeloSalario() {
        givenSalary("5000");
        givenWorkProfile(5, "8");
        givenCycles(net(5000, 4100), net(5000, 4100), net(5000, 4100));

        WishProjection projection = service.project(wish("7200"), service.baselineFor(USER, TODAY), TODAY);

        // sobra de 900: 7.200 / 900 = 8 ciclos. A loja parcelaria olhando o
        // salário inteiro; aqui a parcela é o que realmente sobra
        assertThat(projection.monthsToAfford()).isEqualTo(8);
        assertThat(projection.installments()).isEqualTo(8);
        assertThat(projection.maxInstallment()).isEqualByComparingTo("900.00");
        assertThat(projection.estimatedDate()).isEqualTo(LocalDate.of(2027, 4, 15));
    }

    @Test
    void mesNoVermelhoNaoViraPrazoNegativoNemInfinito() {
        givenSalary("3000");
        givenWorkProfile(5, "8");
        givenCycles(net(3000, 3400), net(3000, 3400), net(3000, 3400));

        WishBaseline baseline = service.baselineFor(USER, TODAY);
        assertThat(baseline.gaps()).contains(WishBaseline.GAP_NO_LEFTOVER);

        WishProjection projection = service.project(wish("18000"), baseline, TODAY);
        assertThat(projection.monthsToAfford()).isNull();
        assertThat(projection.installments()).isNull();
    }

    @Test
    void prazoAlemDeDezAnosNaoViraNumeroGigante() {
        givenSalary("3000");
        givenWorkProfile(5, "8");
        givenCycles(net(3000, 2990), net(3000, 2990), net(3000, 2990));

        // sobra de 10 por mês contra um desejo de 18 mil: 1.800 meses. Mostrar
        // "150 anos" não informa nada que "não fecha" já não diga
        WishProjection projection = service.project(wish("18000"), service.baselineFor(USER, TODAY), TODAY);
        assertThat(projection.monthsToAfford()).isNull();
    }

    // ------------------------------------------------------ cenários de corte

    @Test
    void cortarGastoAntecipaADataEDizQuanto() {
        givenSalary("5000");
        givenWorkProfile(5, "8");
        givenCycles(net(5000, 4000), net(5000, 4000), net(5000, 4000));

        WishProjection projection = service.project(wish("12000"), service.baselineFor(USER, TODAY), TODAY);

        // sobra 1.000 → 12 meses. Cortar 10% de 4.000 = 400 → sobra 1.400 → 9 meses
        assertThat(projection.monthsToAfford()).isEqualTo(12);
        assertThat(projection.whatIfs()).isNotEmpty();
        WishProjection.WhatIf dezPorCento = projection.whatIfs().stream()
                .filter(w -> w.percentOfExpense() == 10).findFirst().orElseThrow();
        assertThat(dezPorCento.monthlyCut()).isEqualByComparingTo("400.00");
        assertThat(dezPorCento.months()).isEqualTo(9);
        assertThat(dezPorCento.monthsEarlier()).isEqualTo(3);
    }

    @Test
    void quemNaoTemSobraAindaRecebeOCaminhoDeSaida() {
        givenSalary("3000");
        givenWorkProfile(5, "8");
        givenCycles(net(3000, 3200), net(3000, 3200), net(3000, 3200));

        WishProjection projection = service.project(wish("2000"), service.baselineFor(USER, TODAY), TODAY);

        // Sem prazo — mas o corte de 10% (320) tira o mês do vermelho e vira a
        // única resposta útil para "não fecha de jeito nenhum"
        assertThat(projection.monthsToAfford()).isNull();
        assertThat(projection.whatIfs()).anySatisfy(w -> {
            assertThat(w.percentOfExpense()).isEqualTo(10);
            assertThat(w.months()).isNotNull();
        });
    }

    @Test
    void cenarioQueNaoAntecipaNadaNaoVaiParaATela() {
        givenSalary("5000");
        givenWorkProfile(5, "8");
        givenCycles(net(5000, 100), net(5000, 100), net(5000, 100));

        // sobra 4.900 contra um desejo de 1.000: já fecha em 1 mês, e nenhum
        // corte pode antecipar o que já é o mínimo
        WishProjection projection = service.project(wish("1000"), service.baselineFor(USER, TODAY), TODAY);
        assertThat(projection.monthsToAfford()).isEqualTo(1);
        assertThat(projection.whatIfs()).isEmpty();
    }

    // ------------------------------------------------------- recorte de ciclo

    @Test
    void semAncoraOsCiclosSaoMesesDoCalendario() {
        List<AnalysisWindow> cycles = service.completedCycles(null, 3, TODAY);

        // agosto está em curso: os completos são julho, junho e maio
        assertThat(cycles).hasSize(3);
        assertThat(cycles.get(0).start()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(cycles.get(0).end()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(cycles.get(2).start()).isEqualTo(LocalDate.of(2026, 5, 1));
    }

    @Test
    void comAncoraOsCiclosSaoOMesDoUsuario() {
        // salário dia 5: em 15/08 o ciclo corrente é 05/08 → 04/09, então o
        // último COMPLETO é 05/07 → 04/08
        List<AnalysisWindow> cycles = service.completedCycles((short) 5, 2, TODAY);

        assertThat(cycles.get(0).start()).isEqualTo(LocalDate.of(2026, 7, 5));
        assertThat(cycles.get(0).end()).isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(cycles.get(1).start()).isEqualTo(LocalDate.of(2026, 6, 5));
    }

    @Test
    void oCicloCorrenteNuncaEntra() {
        // dia 20 ainda não chegou em 15/08: o corrente começou em 20/07
        List<AnalysisWindow> cycles = service.completedCycles((short) 20, 1, TODAY);

        assertThat(cycles.get(0).start()).isEqualTo(LocalDate.of(2026, 6, 20));
        assertThat(cycles.get(0).end()).isEqualTo(LocalDate.of(2026, 7, 19));
        // o ciclo em curso (20/07 → 19/08) tem o salário inteiro e três semanas
        // de gasto; contá-lo mostraria uma sobra que evapora no fim do mês
        assertThat(cycles.get(0).end()).isBefore(LocalDate.of(2026, 7, 20));
    }

    @Test
    void ancoraDia31CaiNoUltimoDiaDeFevereiro() {
        List<AnalysisWindow> cycles = service.completedCycles((short) 31, 6, LocalDate.of(2027, 4, 10));

        // 31/02 não existe: o ciclo começa em 28/02 e termina em 30/03
        assertThat(cycles).anySatisfy(w -> {
            assertThat(w.start()).isEqualTo(LocalDate.of(2027, 2, 28));
            assertThat(w.end()).isEqualTo(LocalDate.of(2027, 3, 30));
        });
    }

    @Test
    void aAncoraDoSalarioRecortaOsCiclosDaBaseline() {
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER))
                .thenReturn(List.of(source(IncomeSource.Kind.SALARY, "5000", (short) 5)));
        givenWorkProfile(5, "8");
        givenCycles(net(5000, 4000));

        service.baselineFor(USER, TODAY);

        // a janela consultada precisa ser o mês do usuário, não o do calendário
        ArgumentCaptor<AnalysisWindow> captor = ArgumentCaptor.forClass(AnalysisWindow.class);
        verify(analyticsService, atLeastOnce()).netFor(eq(USER), captor.capture());
        assertThat(captor.getAllValues().get(0).start()).isEqualTo(LocalDate.of(2026, 7, 5));
    }

    // ------------------------------------------------------------- fixtures

    private void givenSalary(String amount) {
        when(incomeSourceRepository.findAllByUserIdAndActiveTrue(USER))
                .thenReturn(List.of(source(IncomeSource.Kind.SALARY, amount, null)));
    }

    private void givenWorkProfile(int days, String hours) {
        when(workProfileRepository.findById(USER)).thenReturn(Optional.of(WorkProfile.builder()
                .userId(USER)
                .daysPerWeek((short) days)
                .hoursPerDay(new BigDecimal(hours))
                .build()));
    }

    /** Os ciclos são consultados na ordem do mais recente para o mais antigo. */
    private void givenCycles(AnalyticsService.CycleNet first, AnalyticsService.CycleNet... rest) {
        when(analyticsService.netFor(eq(USER), any())).thenReturn(first, rest);
    }

    private static AnalyticsService.CycleNet net(int income, int expense) {
        return new AnalyticsService.CycleNet(
                BigDecimal.valueOf(income), BigDecimal.valueOf(expense));
    }

    private static IncomeSource source(IncomeSource.Kind kind, String amount, Short anchorDay) {
        return IncomeSource.builder()
                .id(UUID.randomUUID())
                .kind(kind)
                .name(kind.name())
                .expectedAmount(new BigDecimal(amount))
                .anchorDay(anchorDay)
                .confirmed(true)
                .active(true)
                .build();
    }

    private static Wish wish(String target) {
        return Wish.builder()
                .id(UUID.randomUUID())
                .name("Moto")
                .targetAmount(new BigDecimal(target))
                .savedAmount(BigDecimal.ZERO)
                .status(Wish.Status.WISH)
                .build();
    }
}
