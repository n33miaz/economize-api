package br.com.economize.service.statement.parser;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * O saldo que o próprio arquivo de extrato declara.
 *
 * <p><b>Por que isto passou a existir, com data.</b> Em 15/09/2026 o dono
 * olhou a Home e disse: <i>"não faz o menor sentido ter sobrado 3.021,06 — não
 * tem nada nas minhas contas"</i>. E na Perspectiva de saldo o app projetava
 * que ele terminaria o mês devendo dezenove mil reais. Os dois números vinham
 * da mesma raiz: o app <b>nunca soube um saldo</b>. Ele somava tudo o que
 * tinha sido importado (entradas menos saídas) e chamava o resultado de saldo.
 *
 * <p>Soma de movimento não é saldo. Bate com o saldo só se o extrato começar
 * no dia em que a conta foi aberta e não faltar uma linha — e, pior, uma
 * fatura de cartão importada joga o total para baixo pelo valor das compras,
 * que depois ainda é descontado de novo quando a fatura é paga pela conta
 * corrente. Era essa conta que produzia os vinte mil negativos.
 *
 * <p><b>O dado sempre esteve no arquivo.</b> O OFX carrega
 * {@code <LEDGERBAL><BALAMT>} com o saldo e {@code <DTASOF>} com a data da
 * leitura; o app lia as transações e jogava o saldo fora. Agora ele entra como
 * o saldo informado da conta — a mesma coluna que o conector do Pluggy
 * preenche —, e as telas param de inventar um.
 *
 * @param amount o saldo. Em conta corrente é quanto existe; em cartão é quanto
 *               se deve, e o sinal vem como o banco escreveu.
 * @param asOf   quando a instituição leu esse saldo. É o que decide qual
 *               leitura é mais nova quando dois arquivos falam da mesma conta.
 */
public record StatementBalance(BigDecimal amount, OffsetDateTime asOf) {
}
