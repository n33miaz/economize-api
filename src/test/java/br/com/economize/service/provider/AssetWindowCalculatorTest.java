package br.com.economize.service.provider;

import br.com.economize.dto.indicator.AssetDetail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Janelas de variação do ativo (EC-103)")
class AssetWindowCalculatorTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 9, 1);

    private static AssetWindowCalculator.Close close(LocalDate date, String price) {
        return new AssetWindowCalculator.Close(date, new BigDecimal(price));
    }

    /** Um fechamento por dia, do dia mais antigo até ontem, todos no mesmo preço. */
    private static List<AssetWindowCalculator.Close> serie(int dias, String preco) {
        List<AssetWindowCalculator.Close> serie = new ArrayList<>();
        for (int i = dias; i >= 1; i--) {
            serie.add(close(HOJE.minusDays(i), preco));
        }
        return serie;
    }

    private static AssetDetail.ChangeWindow janela(List<AssetDetail.ChangeWindow> windows, String key) {
        return windows.stream().filter(w -> w.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("As quatro janelas saem sempre, na ordem do mais curto ao mais longo")
    void quatroJanelasEmOrdem() {
        var windows = AssetWindowCalculator.windows(
                new BigDecimal("10.00"), new BigDecimal("1.5"), List.of(), HOJE);

        assertThat(windows).extracting(AssetDetail.ChangeWindow::key)
                .containsExactly("24h", "7d", "30d", "ytd");
    }

    @Test
    @DisplayName("Hoje vem do provedor, não da série: o fechamento não sabe do intradiário")
    void hojeVemDoProvedor() {
        var windows = AssetWindowCalculator.windows(
                new BigDecimal("10.00"), new BigDecimal("4.11"), serie(60, "10.00"), HOJE);

        assertThat(janela(windows, "24h").changePct()).isEqualByComparingTo("4.11");
    }

    @Test
    @DisplayName("7 dias mede contra o fechamento de sete dias atrás")
    void seteDias() {
        List<AssetWindowCalculator.Close> serie = new ArrayList<>(serie(60, "10.00"));
        serie.add(close(HOJE.minusDays(7), "8.00"));

        var windows = AssetWindowCalculator.windows(
                new BigDecimal("10.00"), BigDecimal.ZERO, serie, HOJE);

        assertThat(janela(windows, "7d").changePct()).isEqualByComparingTo("25.00");
        assertThat(janela(windows, "7d").fromDate()).isEqualTo(HOJE.minusDays(7));
    }

    @Test
    @DisplayName("Alvo em dia sem pregão usa o fechamento ANTERIOR, não desiste")
    void alvoEmFimDeSemana() {
        // A série tem sexta e segunda, mas não o sábado que é o alvo
        List<AssetWindowCalculator.Close> serie = List.of(
                close(HOJE.minusDays(9), "8.00"),
                close(HOJE.minusDays(5), "9.00"));

        var windows = AssetWindowCalculator.windows(
                new BigDecimal("10.00"), BigDecimal.ZERO, serie, HOJE);

        // alvo = HOJE-7 (sem pregão) → cai no fechamento de HOJE-9
        assertThat(janela(windows, "7d").fromDate()).isEqualTo(HOJE.minusDays(9));
        assertThat(janela(windows, "7d").changePct()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("Sem histórico que alcance a janela, a variação é NULA e não zero")
    void semHistoricoDaNulo() {
        // Papel listado há três dias: não existe "30 dias" para ele
        var windows = AssetWindowCalculator.windows(
                new BigDecimal("10.00"), new BigDecimal("2.00"), serie(3, "10.00"), HOJE);

        assertThat(janela(windows, "7d").changePct()).isNull();
        assertThat(janela(windows, "30d").changePct()).isNull();
        // Zero ali afirmaria estabilidade onde não há dado nenhum
        assertThat(janela(windows, "30d").fromPrice()).isNull();
    }

    @Test
    @DisplayName("No ano parte do PRIMEIRO PREGÃO do ano, não de 31 de dezembro")
    void ytdParteDoPrimeiroPregao() {
        List<AssetWindowCalculator.Close> serie = List.of(
                close(LocalDate.of(2025, 12, 31), "5.00"),
                close(LocalDate.of(2026, 1, 2), "8.00"),
                close(HOJE.minusDays(1), "9.90"));

        var windows = AssetWindowCalculator.windows(
                new BigDecimal("10.00"), BigDecimal.ZERO, serie, HOJE);

        // 31/12 pertence ao ano passado: usá-lo faria o YTD carregar a
        // variação de um período que não é este ano
        assertThat(janela(windows, "ytd").fromDate()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(janela(windows, "ytd").changePct()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("Série que termina no ano passado não produz variação no ano")
    void ytdSemPregaoNesteAno() {
        var windows = AssetWindowCalculator.windows(
                new BigDecimal("10.00"), BigDecimal.ZERO,
                List.of(close(LocalDate.of(2025, 11, 20), "8.00")), HOJE);

        assertThat(janela(windows, "ytd").changePct()).isNull();
    }

    @Test
    @DisplayName("Ponto quebrado da série é ignorado, não derruba a conta")
    void pontoQuebradoEIgnorado() {
        List<AssetWindowCalculator.Close> serie = new ArrayList<>();
        serie.add(new AssetWindowCalculator.Close(null, new BigDecimal("9.00")));
        serie.add(new AssetWindowCalculator.Close(HOJE.minusDays(7), null));
        serie.add(new AssetWindowCalculator.Close(HOJE.minusDays(8), BigDecimal.ZERO));
        serie.add(close(HOJE.minusDays(9), "8.00"));

        var windows = AssetWindowCalculator.windows(
                new BigDecimal("10.00"), BigDecimal.ZERO, serie, HOJE);

        assertThat(janela(windows, "7d").fromDate()).isEqualTo(HOJE.minusDays(9));
    }

    @Test
    @DisplayName("Queda vira percentual negativo")
    void quedaENegativa() {
        var windows = AssetWindowCalculator.windows(
                new BigDecimal("8.00"), BigDecimal.ZERO,
                List.of(close(HOJE.minusDays(30), "10.00")), HOJE);

        assertThat(janela(windows, "30d").changePct()).isEqualByComparingTo("-20.00");
    }

    // ------------------------------------------------- a régua de 52 semanas

    @Test
    @DisplayName("A posição na faixa é a fração entre mínima e máxima")
    void posicaoNaFaixa() {
        // PETR4 real em 01/09/2026: (46,87 − 29,31) / (50,69 − 29,31)
        assertThat(AssetWindowCalculator.rangePosition(
                new BigDecimal("46.87"), new BigDecimal("29.31"), new BigDecimal("50.69")))
                .isEqualByComparingTo("0.8213");
    }

    @Test
    @DisplayName("Nas pontas, exatamente 0 e 1")
    void posicaoNasPontas() {
        assertThat(AssetWindowCalculator.rangePosition(
                new BigDecimal("29.31"), new BigDecimal("29.31"), new BigDecimal("50.69")))
                .isEqualByComparingTo("0");
        assertThat(AssetWindowCalculator.rangePosition(
                new BigDecimal("50.69"), new BigDecimal("29.31"), new BigDecimal("50.69")))
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("Preço acima da máxima apurada gruda na ponta, sem sair do trilho")
    void precoForaDaFaixa() {
        // A faixa do provedor é apurada periodicamente: uma máxima nova de hoje
        // ainda não entrou nela
        assertThat(AssetWindowCalculator.rangePosition(
                new BigDecimal("60.00"), new BigDecimal("29.31"), new BigDecimal("50.69")))
                .isEqualByComparingTo("1");
        assertThat(AssetWindowCalculator.rangePosition(
                new BigDecimal("20.00"), new BigDecimal("29.31"), new BigDecimal("50.69")))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Faixa sem largura ou com extremo faltando não tem posição")
    void faixaDegeneradaNaoTemPosicao() {
        // Régua de largura zero: 0 ou 1 colariam o marcador numa ponta por acaso
        assertThat(AssetWindowCalculator.rangePosition(
                new BigDecimal("10.00"), new BigDecimal("10.00"), new BigDecimal("10.00"))).isNull();
        assertThat(AssetWindowCalculator.rangePosition(
                new BigDecimal("10.00"), null, new BigDecimal("12.00"))).isNull();
        assertThat(AssetWindowCalculator.rangePosition(
                null, new BigDecimal("8.00"), new BigDecimal("12.00"))).isNull();
    }
}
