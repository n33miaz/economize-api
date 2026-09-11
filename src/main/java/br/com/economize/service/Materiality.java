package br.com.economize.service;

import java.math.BigDecimal;

/**
 * Abaixo de quanto um número não merece interromper ninguém.
 *
 * <p><b>De onde isto veio.</b> No tour do concorrente, em 09/09/2026, o app
 * abriu uma peça de <b>tela cheia</b>, com gráfico e botão para o CDB deles,
 * dizendo: <i>"Ei, encontrei dinheiro voando! vi R$ 0,13 na sua conta Mercado
 * Pago"</i>. Treze centavos. A peça estava tecnicamente correta e era, ainda
 * assim, um desrespeito com o tempo de quem abriu o aplicativo.
 *
 * <p><b>A distinção que sustenta tudo aqui</b>, e que é o motivo de esta classe
 * existir em vez de um {@code if} solto em cada aviso:
 *
 * <ul>
 *   <li><b>Somas não têm piso.</b> Uma duplicata de R$ 0,13 continua saindo do
 *       total, um estorno de R$ 0,13 continua sendo pareado, um centavo
 *       continua sendo um centavo. Arredondar a conta do usuário seria mentir
 *       para ele, e a mentira pequena é a que corrói a confiança no número
 *       grande.</li>
 *   <li><b>Avisos têm piso.</b> Interromper alguém é gastar atenção dela, e
 *       atenção é o recurso que o app tem em menor quantidade. Um aviso que
 *       aparece por treze centavos ensina o usuário a ignorar avisos — e aí o
 *       aviso que importava passa despercebido junto.</li>
 * </ul>
 *
 * <p>Em uma frase: <b>o piso governa o que se diz, nunca o que se conta.</b>
 */
public final class Materiality {

    /**
     * Cinco reais.
     *
     * <p>Não é um número redondo escolhido no ar: é a menor quantia que aparece
     * sozinha no extrato do dono como uma decisão de gasto (um café, uma
     * passagem, a taxa de R$ 4,00 do Click M que aparece três vezes). Abaixo
     * disso, o que existe no extrato dele é arredondamento, cashback de
     * centavos e crédito de pontos — nada que mude uma escolha.
     *
     * <p>É constante e não configurável de propósito. Um piso que cada tela
     * ajusta volta a ser um {@code if} solto, e o defeito que ele evita é
     * exatamente esse.
     */
    public static final BigDecimal PISO_DE_AVISO = new BigDecimal("5.00");

    private Materiality() {
    }

    /**
     * Este valor merece uma frase na tela do usuário?
     *
     * <p>Compara em módulo: −R$ 0,13 é tão pouco quanto +R$ 0,13, e um aviso
     * sobre saída pequena incomoda igual.
     *
     * @param valor nulo conta como imaterial — um aviso sem número para mostrar
     *              não tem o que dizer
     */
    public static boolean vale(BigDecimal valor) {
        return valor != null && valor.abs().compareTo(PISO_DE_AVISO) >= 0;
    }
}
