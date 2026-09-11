package br.com.economize.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O dinheiro que sai em frase pronta do servidor.
 *
 * <p>Esta suíte existe por causa de um defeito concreto: o recado do vigia
 * concatenava o {@code BigDecimal} direto e entregava "R$ 39216.06" para a
 * tela. Os casos abaixo são os três jeitos de errar isso.
 */
class MoneyTest {

    /** O separador que o {@code NumberFormat} põe depois do "R$". */
    private static String semEspacos(String texto) {
        return texto.replace(" ", " ");
    }

    @Test
    @DisplayName("Milhar leva ponto e centavo leva vírgula, como no Brasil")
    void formatoBrasileiro() {
        assertThat(semEspacos(Money.brl(new BigDecimal("39216.06"))))
                .isEqualTo("R$ 39.216,06");
    }

    @Test
    @DisplayName("Valor redondo mantém as duas casas — não vira 'R$ 1.200'")
    void valorRedondoMantemCentavos() {
        // stripTrailingZeros() era o caminho antigo e comia os centavos aqui,
        // deixando a lista com formatos diferentes um embaixo do outro
        assertThat(semEspacos(Money.brl(new BigDecimal("1200.00"))))
                .isEqualTo("R$ 1.200,00");
    }

    @Test
    @DisplayName("Uma casa decimal vira duas, e não fica pela metade")
    void umaCasaViraDuas() {
        assertThat(semEspacos(Money.brl(new BigDecimal("4.1")))).isEqualTo("R$ 4,10");
    }

    @Test
    @DisplayName("Terceira casa arredonda para cima, e não é truncada")
    void arredondaMeioParaCima() {
        assertThat(semEspacos(Money.brl(new BigDecimal("0.125")))).isEqualTo("R$ 0,13");
    }

    @Test
    @DisplayName("Negativo continua negativo — a frase perderia o sentido sem o sinal")
    void negativoPreservaSinal() {
        assertThat(semEspacos(Money.brl(new BigDecimal("-30.00")))).contains("30,00");
        assertThat(Money.brl(new BigDecimal("-30.00"))).contains("-");
    }
}
