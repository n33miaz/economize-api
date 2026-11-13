package br.com.economize.postgres;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.service.RefundReconciliationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O estorno reivindicado por duas varreduras ao mesmo tempo.
 *
 * <p>A higiene e o botão "procurar estornos" podem rodar juntos. Os dois LEEM
 * o mesmo crédito solto e decidem o mesmo abate; quem desempata é o
 * {@code claimRefund}, um UPDATE condicionado a {@code refund_of_id is null}.
 * Se a trava não segurasse, o crédito de 60 seria abatido duas vezes de uma
 * compra de 100 — 120 cabe no teto da compra, e é mentira.
 *
 * <p>O caso usado é o PARCIAL (crédito menor que a compra), porque é nele que
 * o abate é somado: no estorno inteiro a compra só recebe uma marca, e marca
 * dada duas vezes não se vê.
 */
@DisplayName("Postgres: estorno reivindicado em paralelo — uma varredura só")
class RefundClaimPostgresIT extends PostgresIntegrationBase {

    @Autowired
    private RefundReconciliationService service;

    @Autowired
    private BankTransactionRepository bankTransactionRepository;

    private record Cenario(User user, UUID compra, UUID credito) {
    }

    @RepeatedTest(5)
    @DisplayName("Duas varreduras inteiras ao mesmo tempo: o abate entra uma vez")
    void duasVarredurasAbatemUmaVez() throws Exception {
        Cenario c = compraComEstornoParcial();

        List<Disputa<RefundReconciliationService.Outcome>> r =
                emCorrida(2, i -> service.sweep(c.user().getEmail(), false));

        assertThat(r).allMatch(Disputa::ok);
        // Só uma varredura relata o parcial: a que perdeu sai também do
        // relatório, porque é dele que o desfazer tira os ids que solta
        assertThat(r.stream().mapToInt(d -> d.valor().partials().size()).sum()).isEqualTo(1);
        assertThat(abatido(c.compra())).isEqualByComparingTo("60");
        assertThat(jdbc.queryForObject("select refund_of_id from bank_transactions where id = ?",
                UUID.class, c.credito())).isEqualTo(c.compra());
    }

    @Test
    @DisplayName("Com a trava segura: a segunda reivindicação espera e devolve 0")
    void segundaReivindicacaoEsperaEPerde() throws Exception {
        Cenario c = compraComEstornoParcial();
        UUID outraCompra = tx.execute(s -> bankTransactionRepository.save(
                linha(c.user(), "c2", "-100.00", "MERCADOLIVRE*HOMENOW", 9)).getId());

        // A segunda tenta ligar o MESMO crédito a outra compra: só a reavaliação
        // do WHERE depois da trava impede que o crédito troque de dono
        List<Integer> r = comTravaSegura(
                () -> bankTransactionRepository.claimRefund(c.user().getId(), c.credito(), c.compra()),
                () -> bankTransactionRepository.claimRefund(c.user().getId(), c.credito(), outraCompra));

        assertThat(r).containsExactly(1, 0);
        assertThat(jdbc.queryForObject("select refund_of_id from bank_transactions where id = ?",
                UUID.class, c.credito())).isEqualTo(c.compra());
    }

    /** Compra de 100 e um crédito de 60 que a NOMEIA: estorno parcial (EC-236). */
    private Cenario compraComEstornoParcial() {
        User user = novoUsuario();
        return tx.execute(s -> {
            BankTransaction compra = bankTransactionRepository.save(
                    linha(user, "c1", "-100.00", "MERCADOLIVRE*HOMENOW", 10));
            BankTransaction credito = bankTransactionRepository.save(
                    linha(user, "e1", "60.00", "Estorno de \"Mercadolivre*Homenow\" (Mercado Livre)", 12));
            return new Cenario(user, compra.getId(), credito.getId());
        });
    }

    private BigDecimal abatido(UUID compra) {
        return jdbc.queryForObject("select refunded_amount from bank_transactions where id = ?",
                BigDecimal.class, compra);
    }

    private static BankTransaction linha(User user, String id, String valor, String descricao, int dia) {
        return BankTransaction.builder().user(user).transactionId(id)
                .type(valor.startsWith("-") ? "DEBIT" : "CREDIT")
                .amount(new BigDecimal(valor)).description(descricao)
                .date(OffsetDateTime.of(2026, 9, dia, 12, 0, 0, 0, ZoneOffset.UTC))
                .reviewStatus(BankTransaction.ReviewStatus.SUGGESTED).build();
    }
}
