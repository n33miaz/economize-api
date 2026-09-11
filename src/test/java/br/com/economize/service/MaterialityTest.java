package br.com.economize.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O piso que existe por causa de treze centavos.
 *
 * <p>No tour do concorrente o app abriu uma peça de tela cheia, com gráfico e
 * botão de investimento, para anunciar <b>R$ 0,13</b>. Estava correto e era um
 * desrespeito com o tempo de quem abriu o aplicativo.
 */
class MaterialityTest {

    @Test
    @DisplayName("Os treze centavos do concorrente não valem uma tela")
    void osTrezeCentavos() {
        assertThat(Materiality.vale(new BigDecimal("0.13"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "0.13", "1.00", "4.99", "-0.13", "-4.99"})
    @DisplayName("Abaixo do piso não interrompe ninguém, em qualquer sinal")
    void abaixoDoPiso(String valor) {
        // Módulo: uma saída de R$ 0,13 incomoda tanto quanto uma entrada
        assertThat(Materiality.vale(new BigDecimal(valor))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.00", "5.01", "180.00", "-5.00", "-539.70"})
    @DisplayName("Do piso para cima, merece frase")
    void doPisoParaCima(String valor) {
        assertThat(Materiality.vale(new BigDecimal(valor))).isTrue();
    }

    @Test
    @DisplayName("A borda é inclusiva: cinco reais exatos já contam")
    void bordaInclusiva() {
        assertThat(Materiality.vale(new BigDecimal("5.00"))).isTrue();
        assertThat(Materiality.vale(new BigDecimal("4.999"))).isFalse();
    }

    @Test
    @DisplayName("Escala não muda o veredito: 5 e 5,0000 são o mesmo dinheiro")
    void escalaNaoDecide() {
        // compareTo, e não equals: BigDecimal("5.00").equals(new BigDecimal("5"))
        // é false, e um piso que depende de casas decimais é um piso quebrado
        assertThat(Materiality.vale(new BigDecimal("5"))).isTrue();
        assertThat(Materiality.vale(new BigDecimal("5.0000"))).isTrue();
    }

    @Test
    @DisplayName("Nulo é imaterial: aviso sem número não tem o que dizer")
    void nuloEImaterial() {
        assertThat(Materiality.vale(null)).isFalse();
    }

    @Test
    @DisplayName("Zero não interrompe")
    void zeroNaoInterrompe() {
        assertThat(Materiality.vale(BigDecimal.ZERO)).isFalse();
    }
}
