package br.com.economize.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O que vai do usuário para o log.
 *
 * <p>Esta suíte existe porque sobravam dez pontos de <i>log injection</i>
 * depois da troca e-mail → id: ticker, moeda e chave de snapshot iam crus do
 * URL para a linha de log.
 */
class LogSafeTest {

    @Test
    @DisplayName("Quebra de linha no valor não forja uma segunda linha de log")
    void quebraDeLinhaNaoForjaLinha() {
        String forjado = "PETR4\n2026-09-11 ERROR Banco fora do ar";

        String saneado = LogSafe.value(forjado);

        assertThat(saneado).doesNotContain("\n").doesNotContain("\r");
        assertThat(saneado).startsWith("PETR4 2026-09-11");
    }

    @Test
    @DisplayName("Tab, retorno de carro e controle ASCII viram espaço")
    void controlesViramEspaco() {
        assertThat(LogSafe.value("a\tb\rcd")).isEqualTo("a b c d");
    }

    @Test
    @DisplayName("Valor comum passa intacto — sanear não é esconder")
    void valorComumPassaIntacto() {
        assertThat(LogSafe.value("USD-BRL")).isEqualTo("USD-BRL");
        assertThat(LogSafe.value("search:PETR4.SA")).isEqualTo("search:PETR4.SA");
    }

    @Test
    @DisplayName("Valor gigante é cortado com reticência, e não engole o arquivo")
    void valorGiganteECortado() {
        String enorme = "x".repeat(500);

        String saneado = LogSafe.value(enorme);

        assertThat(saneado).hasSize(80).endsWith("…");
    }

    @Test
    @DisplayName("Nulo continua nulo: 'null' no log é informação")
    void nuloContinuaNulo() {
        assertThat(LogSafe.value(null)).isNull();
    }

    @Test
    @DisplayName("E-mail vira primeira letra e domínio — depura sem ser PII")
    void emailMascarado() {
        assertThat(LogSafe.email("neemias@example.com")).isEqualTo("n***@example.com");
    }

    @Test
    @DisplayName("E-mail torto não vaza nem quebra")
    void emailTortoNaoVaza() {
        assertThat(LogSafe.email("sem-arroba")).isEqualTo("***");
        assertThat(LogSafe.email("@dominio.com")).isEqualTo("***");
        assertThat(LogSafe.email("  ")).isNull();
        assertThat(LogSafe.email(null)).isNull();
    }

    @Test
    @DisplayName("E-mail com quebra de linha é saneado ANTES de mascarar")
    void emailComQuebraESaneado() {
        assertThat(LogSafe.email("a\nb@x.com")).doesNotContain("\n");
    }
}
