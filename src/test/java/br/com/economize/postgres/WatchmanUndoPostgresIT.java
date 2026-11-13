package br.com.economize.postgres;

import br.com.economize.exception.ResourceConflictException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.SweepRun;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.SweepRunRepository;
import br.com.economize.service.WatchmanService;
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
 * Dois "desfazer" da mesma passada do vigia ao mesmo tempo.
 *
 * <p>O desfazer lê {@code undone_at} e depois marca. Ler e salvar não é
 * atômico: os dois pedidos passariam pela leitura e soltariam as linhas duas
 * vezes. Quem decide é o UPDATE condicional ({@code markUndone}); o perdedor
 * tem de receber o 409 ("já foi desfeita"), e não seguir adiante.
 */
@DisplayName("Postgres: desfazer do vigia — dois pedidos, uma liberação")
class WatchmanUndoPostgresIT extends PostgresIntegrationBase {

    @Autowired
    private WatchmanService watchmanService;

    @Autowired
    private SweepRunRepository sweepRunRepository;

    @Autowired
    private BankTransactionRepository bankTransactionRepository;

    private record Passada(User user, UUID runId, UUID linha) {
    }

    @RepeatedTest(5)
    @DisplayName("Dois desfazer simultâneos pelo serviço: um libera, o outro recebe conflito")
    void doisDesfazerUmLibera() throws Exception {
        Passada p = passadaDeDuplicata();

        List<Disputa<WatchmanService.Note>> r =
                emCorrida(2, i -> watchmanService.undo(p.user().getEmail(), p.runId()));

        assertThat(r).filteredOn(Disputa::ok).hasSize(1);
        assertThat(r).filteredOn(d -> !d.ok()).singleElement()
                .satisfies(d -> assertThat(d.erro()).isInstanceOf(ResourceConflictException.class));
        assertThat(jdbc.queryForObject("select ignored from bank_transactions where id = ?",
                Boolean.class, p.linha())).isFalse();
        assertThat(jdbc.queryForObject("select undone_at from sweep_runs where id = ?",
                OffsetDateTime.class, p.runId())).isNotNull();
    }

    @Test
    @DisplayName("Com a trava segura: a segunda marcação espera e devolve 0")
    void marcacaoComTravaSegura() throws Exception {
        Passada p = passadaDeDuplicata();

        List<Integer> r = comTravaSegura(
                () -> sweepRunRepository.markUndone(p.runId(), p.user().getId(), OffsetDateTime.now()),
                () -> sweepRunRepository.markUndone(p.runId(), p.user().getId(), OffsetDateTime.now()));

        assertThat(r).containsExactly(1, 0);
    }

    /** Uma passada do vigia da cópia que marcou uma linha como duplicata. */
    private Passada passadaDeDuplicata() {
        User user = novoUsuario();
        return tx.execute(s -> {
            BankTransaction linha = bankTransactionRepository.save(BankTransaction.builder()
                    .user(user).transactionId("dup-1").type("DEBIT")
                    .amount(new BigDecimal("-50.00")).description("MERCADO")
                    .date(OffsetDateTime.of(2026, 9, 10, 12, 0, 0, 0, ZoneOffset.UTC))
                    .reviewStatus(BankTransaction.ReviewStatus.SUGGESTED)
                    .ignored(true).ignoredReason(BankTransaction.IgnoredReason.DUPLICATE)
                    .build());
            SweepRun run = sweepRunRepository.save(SweepRun.builder().user(user)
                    .kind(SweepRun.Kind.DUPLICATE).affected(1).volume(new BigDecimal("50"))
                    .build());
            sweepRunRepository.addItems(run.getId(), List.of(linha.getId()));
            return new Passada(user, run.getId(), linha.getId());
        });
    }
}
