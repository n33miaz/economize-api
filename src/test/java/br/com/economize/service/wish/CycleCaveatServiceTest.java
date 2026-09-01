package br.com.economize.service.wish;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.analytics.CycleCaveat;
import br.com.economize.model.IncomeSource;
import br.com.economize.repository.IncomeSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Ressalvas do ciclo (EC-138) — o que o total não diz")
class CycleCaveatServiceTest {

    private IncomeSourceRepository repository;
    private CycleCaveatService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(IncomeSourceRepository.class);
        when(repository.findAllByUserIdAndActiveTrue(any())).thenReturn(List.of());
        service = new CycleCaveatService(repository);
    }

    private IncomeSource fonte(String nome, int dia, String valor) {
        return IncomeSource.builder()
                .id(UUID.randomUUID())
                .kind(IncomeSource.Kind.MEAL_VOUCHER)
                .name(nome)
                .anchorDay((short) dia)
                .expectedAmount(new BigDecimal(valor))
                .active(true)
                .confirmed(true)
                .build();
    }

    private List<CycleCaveat> ressalvas(AnalysisWindow janela, boolean anteriorTemDado, LocalDate hoje) {
        return service.caveatsFor(userId, janela, anteriorTemDado, hoje);
    }

    @Test
    @DisplayName("VR que cai no fim do ciclo vira ressalva: o gasto é do mês seguinte")
    void vrNoFimDoCiclo() {
        when(repository.findAllByUserIdAndActiveTrue(any()))
                .thenReturn(List.of(fonte("Vale-refeição", 28, "600.00")));

        var lista = ressalvas(AnalysisWindow.ofMonth(java.time.YearMonth.of(2026, 8)),
                true, LocalDate.of(2026, 9, 5));

        assertThat(lista).extracting(CycleCaveat::kind).contains(CycleCaveat.Kind.LATE_INCOME);
        CycleCaveat r = lista.stream().filter(c -> c.kind() == CycleCaveat.Kind.LATE_INCOME).findFirst().orElseThrow();
        assertThat(r.title()).contains("Vale-refeição");
        assertThat(r.detail()).contains("28/08").contains("próximo mês");
        assertThat(r.amount()).isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("Salário no começo do ciclo NÃO vira ressalva — seria ruído todo mês")
    void salarioNoComecoNaoRessalva() {
        when(repository.findAllByUserIdAndActiveTrue(any()))
                .thenReturn(List.of(fonte("Salário", 5, "5000.00")));

        var lista = ressalvas(AnalysisWindow.ofMonth(java.time.YearMonth.of(2026, 8)),
                true, LocalDate.of(2026, 9, 5));

        assertThat(lista).extracting(CycleCaveat::kind).doesNotContain(CycleCaveat.Kind.LATE_INCOME);
    }

    @Test
    @DisplayName("Ciclo aberto avisa que o total é parcial")
    void cicloAberto() {
        var lista = ressalvas(AnalysisWindow.ofMonth(java.time.YearMonth.of(2026, 8)),
                true, LocalDate.of(2026, 8, 12));

        CycleCaveat r = lista.stream().filter(c -> c.kind() == CycleCaveat.Kind.PARTIAL_PERIOD)
                .findFirst().orElseThrow();
        // 12 dias corridos de 31: comparar isso com um mês inteiro produziria
        // uma economia que ninguém fez
        assertThat(r.detail()).contains("12 de 31");
    }

    @Test
    @DisplayName("Ciclo fechado não avisa nada sobre parcialidade")
    void cicloFechado() {
        var lista = ressalvas(AnalysisWindow.ofMonth(java.time.YearMonth.of(2026, 8)),
                true, LocalDate.of(2026, 9, 1));

        assertThat(lista).extracting(CycleCaveat::kind).doesNotContain(CycleCaveat.Kind.PARTIAL_PERIOD);
    }

    @Test
    @DisplayName("Sem período anterior com movimento, a variação não significa nada")
    void semAnterior() {
        var lista = ressalvas(AnalysisWindow.ofMonth(java.time.YearMonth.of(2026, 8)),
                false, LocalDate.of(2026, 9, 5));

        assertThat(lista).extracting(CycleCaveat::kind).contains(CycleCaveat.Kind.NO_PREVIOUS_DATA);
    }

    @Test
    @DisplayName("Mês sem o dia da âncora usa o último dia — dia 31 em fevereiro")
    void ancoraForaDoMes() {
        // O pagamento acontece no último dia útil possível; procurar o dia 31
        // literalmente devolveria "não caiu neste mês" para todo fevereiro
        LocalDate queda = CycleCaveatService.quedaDentroDa(
                AnalysisWindow.ofMonth(java.time.YearMonth.of(2026, 2)), (short) 31);

        assertThat(queda).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    @DisplayName("Janela ancorada encontra a queda mesmo atravessando a virada do mês")
    void janelaAtravessandoMeses() {
        // 12/07 → 11/08 com âncora dia 5: a única queda dentro da janela é
        // 05/08, do mês seguinte ao do início
        LocalDate queda = CycleCaveatService.quedaDentroDa(
                AnalysisWindow.of(LocalDate.of(2026, 7, 12), LocalDate.of(2026, 8, 11)), (short) 5);

        assertThat(queda).isEqualTo(LocalDate.of(2026, 8, 5));
    }

    @Test
    @DisplayName("Âncora que não cai na janela devolve nulo em vez de chutar uma data")
    void ancoraForaDaJanela() {
        LocalDate queda = CycleCaveatService.quedaDentroDa(
                AnalysisWindow.of(LocalDate.of(2026, 7, 12), LocalDate.of(2026, 7, 20)), (short) 5);

        assertThat(queda).isNull();
    }

    @Test
    @DisplayName("Fonte sem âncora é ignorada — não dá para saber quando caiu")
    void fonteSemAncora() {
        IncomeSource semAncora = fonte("Freela", 1, "800.00");
        semAncora.setAnchorDay(null);
        when(repository.findAllByUserIdAndActiveTrue(any())).thenReturn(List.of(semAncora));

        var lista = ressalvas(AnalysisWindow.ofMonth(java.time.YearMonth.of(2026, 8)),
                true, LocalDate.of(2026, 9, 5));

        assertThat(lista).extracting(CycleCaveat::kind).doesNotContain(CycleCaveat.Kind.LATE_INCOME);
    }

    @Test
    @DisplayName("Ciclo fechado e comparável não produz ressalva nenhuma")
    void mesLimpo() {
        var lista = ressalvas(AnalysisWindow.ofMonth(java.time.YearMonth.of(2026, 8)),
                true, LocalDate.of(2026, 9, 10));

        // A ressalva só vale se for rara: um aviso em todo mês vira ruído e
        // deixa de ser lido justamente quando importa
        assertThat(lista).isEmpty();
    }
}
