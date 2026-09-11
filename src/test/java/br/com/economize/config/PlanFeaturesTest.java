package br.com.economize.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EC-207 — preço sem degrau.
 *
 * <p><b>A regra que este teste existe para travar:</b> o número de contas
 * bancárias nunca é o que se cobra. Conectar o segundo banco não custa mais do
 * que o primeiro — quem tem três contas não é mais rico, é só mais bagunçado,
 * e o app existe justamente para essa pessoa. Cobrar por conta seria cobrar
 * mais de quem mais precisa.
 *
 * <p><b>Por que um teste, e não só uma decisão.</b> A tabela de planos é
 * <i>texto de oferta</i>: ela muda com a estratégia comercial, por properties,
 * sem deploy e sem revisão de código. É exatamente o tipo de lugar em que um
 * "até 2 bancos" entra numa tarde e ninguém percebe até alguém reclamar.
 *
 * <p><b>E "ilimitado" também reprova.</b> Não havia limite nenhum no gratuito,
 * então anunciar "conexão bancária ilimitada" no pago <b>inventava</b> o
 * degrau: quem lê entende que existe um teto em algum lugar e passa a procurar
 * por ele. A ausência de limite se comunica não falando em limite.
 */
class PlanFeaturesTest {

    /** As formas de fazer o número de contas virar preço. */
    private static final List<Pattern> DEGRAUS = List.of(
            // "ilimitado/ilimitada" — inventa o teto que não existe
            Pattern.compile("ilimitad"),
            // "até 2 bancos", "1 conta", "3 conexoes"
            Pattern.compile("\\b\\d+\\s*(banco|conta|conexao|conexoes|instituicao)"),
            Pattern.compile("\\bate\\s+\\d+"),
            // "limite de contas", "máximo de bancos"
            Pattern.compile("(limite|maximo)\\s+de\\s+(banco|conta|conexao)"));

    private static String semAcento(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private static Stream<String> todasAsVantagens() {
        PlanProperties props = new PlanProperties();
        return Stream.concat(
                props.getFree().getFeatures().stream(),
                props.getPlus().getFeatures().stream());
    }

    @Test
    @DisplayName("Nenhum plano vende quantidade de banco, conta ou conexão")
    void nenhumPlanoVendeQuantidadeDeConta() {
        List<String> ofensores = todasAsVantagens()
                .filter(vantagem -> DEGRAUS.stream()
                        .anyMatch(degrau -> degrau.matcher(semAcento(vantagem)).find()))
                .toList();

        assertThat(ofensores)
                .as("o número de contas nunca é o que se cobra (EC-207)")
                .isEmpty();
    }

    @Test
    @DisplayName("O gratuito DIZ que tem conexão bancária — o silêncio criava a dúvida")
    void gratuitoDizQueTemConexao() {
        PlanProperties props = new PlanProperties();

        // Não falar de conexão no gratuito é o que fazia a palavra "ilimitada"
        // do pago parecer vantagem sobre um teto que não existe
        assertThat(props.getFree().getFeatures())
                .anySatisfy(vantagem ->
                        assertThat(semAcento(vantagem)).contains("conexao bancaria"));
    }

    @Test
    @DisplayName("O gratuito não custa nada, e o pago custa algo — a tabela é legível")
    void precosCoerentes() {
        PlanProperties props = new PlanProperties();

        assertThat(props.getFree().getPriceMonthly()).isEqualByComparingTo("0");
        assertThat(props.getPlus().getPriceMonthly()).isGreaterThan(java.math.BigDecimal.ZERO);
        assertThat(props.getFree().getFeatures()).isNotEmpty();
        assertThat(props.getPlus().getFeatures()).isNotEmpty();
    }

    @Test
    @DisplayName("A guarda enxerga um degrau de verdade — senão ela não vale nada")
    void aGuardaPegaODegrau() {
        List<String> falsas = List.of(
                "Conexão bancária ilimitada",
                "Até 2 bancos conectados",
                "1 conta bancária",
                "Limite de contas ampliado");

        for (String vantagem : falsas) {
            assertThat(DEGRAUS.stream().anyMatch(d -> d.matcher(semAcento(vantagem)).find()))
                    .as("deveria pegar: %s", vantagem)
                    .isTrue();
        }
    }
}
