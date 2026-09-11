package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.SweepRun;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.SweepRunRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Os vigias que trabalham sozinhos — com nome, recado e desfazer (EC-202).
 *
 * <p><b>O defeito, e ele é nosso.</b> Seis varreduras rodam depois de cada
 * importação e <b>mudam os números do usuário</b>: movimentação própria,
 * aplicação/resgate, transferência da casa, duplicatas, estornos e
 * recorrência. Até aqui não deixavam rastro nenhum. O dono abre o app, a soma
 * mudou, e não há uma linha em lugar nenhum dizendo o que aconteceu.
 *
 * <p>Cada marca sempre foi reversível linha a linha — mas ninguém sabia
 * <b>quais</b> linhas olhar. Reversível na teoria e invisível na prática é o
 * mesmo que irreversível.
 *
 * <p><b>O que o concorrente faz melhor, e o que faz pior.</b> O Pierre dá
 * nome e ilustração aos agentes dele, e isso funciona: um "vigia" com cara e
 * função declarada é mais fácil de confiar do que "o sistema". O que ele não
 * faz é prestar contas — não há histórico do que cada agente mexeu, nem como
 * desfazer. É a metade fácil da ideia.
 *
 * <p><b>Desfazer não apaga o registro.</b> A passada continua no histórico
 * marcada como desfeita: apagá-la esconderia que o vigia errou, que é
 * exatamente o que o histórico existe para mostrar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchmanService {

    private final SweepRunRepository sweepRunRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final UserRepository userRepository;

    /**
     * Registra o que uma passada fez.
     *
     * @param touched as linhas que ESTA passada mudou — nunca as que já
     *                estavam marcadas antes. Incluir uma linha que outra
     *                pessoa (ou o próprio usuário) marcou faria o desfazer
     *                desfazer decisão alheia.
     */
    @Transactional
    public SweepRun record(User user, SweepRun.Kind kind, int affected, BigDecimal volume,
                           Collection<UUID> touched) {
        SweepRun run = sweepRunRepository.save(SweepRun.builder()
                .user(user).kind(kind).affected(affected).volume(volume).build());
        if (touched != null && !touched.isEmpty()) {
            sweepRunRepository.addItems(run.getId(), touched);
        }
        return run;
    }

    /** Os recados, do mais recente para o mais antigo. */
    @Transactional(readOnly = true)
    public List<Note> notesFor(String email) {
        User user = requireUser(email);
        return sweepRunRepository.findTop50ByUserIdOrderByRanAtDesc(user.getId()).stream()
                .map(run -> new Note(run.getId(), run.getKind(),
                        nameOf(run.getKind()), roleOf(run.getKind()),
                        message(run), run.getAffected(), run.getVolume(),
                        run.getRanAt(), run.getUndoneAt() != null,
                        canUndo(run)))
                .toList();
    }

    /**
     * Desfaz uma passada: solta exatamente as linhas que ela marcou.
     *
     * <p>Só o que ela marcou. Uma linha que o usuário marcou à mão depois
     * continua marcada, porque decisão de gente vence varredura — em
     * qualquer direção.
     */
    @Transactional
    public Note undo(String email, UUID runId) {
        User user = requireUser(email);
        SweepRun run = sweepRunRepository.findByIdAndUserId(runId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Passada não encontrada"));
        if (run.getUndoneAt() != null) {
            throw new IllegalStateException("Esta passada já foi desfeita");
        }
        if (!canUndo(run)) {
            throw new IllegalStateException(
                    "A detecção de recorrência não se desfaz por aqui: apague a série na tela de "
                            + "Recorrências, que é onde ela vive");
        }

        List<UUID> ids = sweepRunRepository.findItemIds(runId);
        if (!ids.isEmpty()) {
            switch (run.getKind()) {
                case INTERNAL_TRANSFER, INVESTMENT_FLOW ->
                        bankTransactionRepository.clearInternalTransfer(user.getId(), ids);
                case FAMILY_TRANSFER ->
                        bankTransactionRepository.clearFamilyTransfer(user.getId(), ids);
                case DUPLICATE -> bankTransactionRepository.clearIgnored(user.getId(), ids);
                case REFUND -> bankTransactionRepository.clearRefund(user.getId(), ids);
                default -> throw new IllegalStateException("Vigia sem desfazer: " + run.getKind());
            }
        }
        run.setUndoneAt(OffsetDateTime.now());
        sweepRunRepository.save(run);
        log.info("Passada desfeita: {} com {} linha(s), user={}",
                run.getKind(), ids.size(), user.getId());
        return new Note(run.getId(), run.getKind(), nameOf(run.getKind()), roleOf(run.getKind()),
                message(run), run.getAffected(), run.getVolume(), run.getRanAt(), true, false);
    }

    /**
     * A recorrência não se desfaz por aqui.
     *
     * <p>Ela não marca linha nenhuma: cria séries, que vivem na tela de
     * Recorrências e já têm o próprio descarte. Oferecer um botão que não faz
     * o que promete é pior do que não oferecer.
     */
    private static boolean canUndo(SweepRun run) {
        return run.getUndoneAt() == null && run.getKind() != SweepRun.Kind.RECURRENCE;
    }

    /** O nome do vigia, como a tela o chama. */
    public static String nameOf(SweepRun.Kind kind) {
        return switch (kind) {
            case INTERNAL_TRANSFER -> "Vigia do bolso";
            case INVESTMENT_FLOW -> "Vigia da poupança";
            case FAMILY_TRANSFER -> "Vigia da casa";
            case DUPLICATE -> "Vigia da cópia";
            case REFUND -> "Vigia do estorno";
            case RECURRENCE -> "Vigia do que se repete";
        };
    }

    /** Uma linha dizendo o que ele faz. Nunca duas. */
    public static String roleOf(SweepRun.Kind kind) {
        return switch (kind) {
            case INTERNAL_TRANSFER -> "Tira das somas o dinheiro que só trocou de conta sua.";
            case INVESTMENT_FLOW -> "Aplicar não é gastar; resgatar não é ganhar.";
            case FAMILY_TRANSFER -> "Tira da soma da casa o dinheiro que ficou dentro dela.";
            case DUPLICATE -> "Acha a linha que entrou pela conexão E por um arquivo.";
            case REFUND -> "A compra que voltou para de contar.";
            case RECURRENCE -> "Descobre o que se repete todo mês.";
        };
    }

    /**
     * O recado da passada.
     *
     * <p>Achou nada é frase, não silêncio: sem ela o usuário não sabe se o
     * vigia trabalhou ou se quebrou.
     */
    static String message(SweepRun run) {
        int n = run.getAffected();
        if (n == 0) return "Passei e não achei nada novo.";
        String quantas = switch (run.getKind()) {
            case DUPLICATE -> n == 1 ? "1 par duplicado" : n + " pares duplicados";
            case REFUND -> n == 1 ? "1 estorno pareado" : n + " estornos pareados";
            case RECURRENCE -> n == 1 ? "1 série" : n + " séries";
            default -> n == 1 ? "1 lançamento" : n + " lançamentos";
        };
        String verbo = run.getKind() == SweepRun.Kind.RECURRENCE ? "Reconheci " : "Encontrei ";
        String recado = verbo + quantas + ".";
        if (run.getVolume() != null && run.getVolume().signum() != 0) {
            // Formatado aqui, e não concatenado cru: esta frase vai inteira
            // para a tela, e um "R$ 39216.06" no meio de um app em português
            // é a linha que faz o usuário duvidar do resto
            recado += " Isso muda " + Money.brl(run.getVolume()) + " nas suas somas.";
        }
        return recado;
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }

    /**
     * @param canUndo false quando já foi desfeita ou quando o vigia não tem
     *                desfazer — a tela precisa saber ANTES de desenhar o botão
     */
    public record Note(UUID runId, SweepRun.Kind kind, String watchman, String role,
                       String message, int affected, BigDecimal volume,
                       OffsetDateTime ranAt, boolean undone, boolean canUndo) {
    }
}
