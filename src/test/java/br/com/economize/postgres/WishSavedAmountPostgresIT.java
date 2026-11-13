package br.com.economize.postgres;

import br.com.economize.model.User;
import br.com.economize.model.Wish;
import br.com.economize.repository.WishRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aportes simultâneos numa meta.
 *
 * <p>O saldo muda na própria linha ({@code saved_amount = saved_amount + delta})
 * para que dois aportes não leiam o mesmo valor e um sobrescreva o outro. E a
 * retirada só passa se o saldo NO MOMENTO DA ESCRITA comportar: a conferência
 * do serviço lê antes, e duas retiradas que cabem sozinhas não cabem juntas.
 *
 * <p>Testado no repositório, que é onde mora a trava: o serviço de aportes
 * acrescenta validação e histórico, mas a corrida se decide neste UPDATE.
 */
@DisplayName("Postgres: aporte em meta — nada se perde, nada fica negativo")
class WishSavedAmountPostgresIT extends PostgresIntegrationBase {

    private static final BigDecimal TETO = new BigDecimal("999999999999999.9999");
    private static final List<Wish.Status> ENCERRADOS =
            List.of(Wish.Status.PURCHASED, Wish.Status.ARCHIVED);

    /** Abaixo do pool padrão do Hikari (10): cada thread segura uma conexão. */
    private static final int THREADS = 8;

    @Autowired
    private WishRepository wishRepository;

    private record Meta(UUID userId, UUID wishId) {
    }

    @RepeatedTest(5)
    @DisplayName("N aportes ao mesmo tempo: o saldo é a soma de todos")
    void aportesSimultaneosSomamTodos() throws Exception {
        Meta m = meta("0");

        // 10, 11, 12, ... — valores distintos: um aporte perdido muda a soma
        List<Disputa<Integer>> r = emCorrida(THREADS, i -> wishRepository.adjustSavedAmount(
                m.wishId(), m.userId(), BigDecimal.valueOf(10 + i), TETO, ENCERRADOS));

        assertThat(r).allMatch(d -> d.ok() && d.valor() == 1);
        int esperado = 0;
        for (int i = 0; i < THREADS; i++) esperado += 10 + i;
        assertThat(saldo(m)).isEqualByComparingTo(BigDecimal.valueOf(esperado));
    }

    @RepeatedTest(5)
    @DisplayName("Duas retiradas que só cabem sozinhas: uma passa, o saldo não fica negativo")
    void retiradasSimultaneasNaoNegativam() throws Exception {
        Meta m = meta("100");

        List<Disputa<Integer>> r = emCorrida(2, i -> wishRepository.adjustSavedAmount(
                m.wishId(), m.userId(), new BigDecimal("-60"), TETO, ENCERRADOS));

        assertThat(r).allMatch(Disputa::ok);
        assertThat(r.stream().mapToInt(Disputa::valor).sum()).isEqualTo(1);
        assertThat(saldo(m)).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("Com a trava segura: o segundo aporte espera e soma sobre o primeiro")
    void aporteComTravaSeguraSoma() throws Exception {
        Meta m = meta("0");

        List<Integer> r = comTravaSegura(
                () -> wishRepository.adjustSavedAmount(m.wishId(), m.userId(),
                        new BigDecimal("30"), TETO, ENCERRADOS),
                () -> wishRepository.adjustSavedAmount(m.wishId(), m.userId(),
                        new BigDecimal("45"), TETO, ENCERRADOS));

        assertThat(r).containsExactly(1, 1);
        assertThat(saldo(m)).isEqualByComparingTo("75");
    }

    @Test
    @DisplayName("Com a trava segura: a segunda retirada reavalia o saldo e é recusada")
    void retiradaComTravaSeguraRecusada() throws Exception {
        Meta m = meta("100");

        List<Integer> r = comTravaSegura(
                () -> wishRepository.adjustSavedAmount(m.wishId(), m.userId(),
                        new BigDecimal("-60"), TETO, ENCERRADOS),
                () -> wishRepository.adjustSavedAmount(m.wishId(), m.userId(),
                        new BigDecimal("-60"), TETO, ENCERRADOS));

        assertThat(r).containsExactly(1, 0);
        assertThat(saldo(m)).isEqualByComparingTo("40");
    }

    private Meta meta(String saldoInicial) {
        User user = novoUsuario();
        UUID id = tx.execute(s -> wishRepository.save(Wish.builder().user(user).name("Viagem")
                .targetAmount(new BigDecimal("100000")).savedAmount(new BigDecimal(saldoInicial))
                .status(Wish.Status.GOAL).build()).getId());
        return new Meta(user.getId(), id);
    }

    /** Relido do banco: {@code saved_amount} não é atualizável pela entidade. */
    private BigDecimal saldo(Meta m) {
        return jdbc.queryForObject("select saved_amount from wishes where id = ?",
                BigDecimal.class, m.wishId());
    }
}
