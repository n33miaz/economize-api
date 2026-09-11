package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.SweepRun;
import br.com.economize.model.User;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.family.FamilyTransferService;
import br.com.economize.service.recurrence.RecurrenceDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * A faxina que roda depois de cada importação — e por que ela existe.
 *
 * <p>Em 07/09/2026 três correções foram aplicadas à mão no extrato do dono: as
 * transferências entre contas dele mesmo, as transferências entre ele e a
 * esposa e as 20 linhas que tinham entrado por duas fontes. A Casa saiu de
 * R$ 8.225,91 de receita em agosto para R$ 6.017,17, que é a renda real do
 * casal.
 *
 * <p><b>Nenhuma dessas correções sobreviveria ao próximo arquivo importado.</b>
 * Elas foram chamadas uma vez, por endpoint; a importação seguinte traria
 * linhas novas sem marca nenhuma e os mesmos erros voltariam — a mesma
 * transferência contada duas vezes, a mesma linha em duplicidade. Este serviço
 * transforma aquelas três chamadas numa etapa fixa do caminho de entrada de
 * dados.
 *
 * <p><b>A ordem importa</b>, e é esta:
 * <ol>
 *   <li>movimentação própria — marca o que é dinheiro do titular trocando de
 *       bolso;</li>
 *   <li>aplicação e resgate — a outra ponta do mesmo bolso, quando ela é um
 *       investimento do dono em vez de outra conta. Junto da etapa acima
 *       porque é a mesma pergunta e a mesma coluna; medido no extrato do
 *       dono: 350 linhas e R$ 39.216,06 de movimento que nunca existiu;</li>
 *   <li>transferência entre pessoas da casa — o que sobrou e circulou entre o
 *       casal;</li>
 *   <li>duplicatas — o que entrou por duas portas. Depois das duas marcas
 *       acima, porque a varredura pula o que já está marcado como ignorado e
 *       não faz sentido parear linhas que já saíram das somas;</li>
 *   <li>estornos — a compra que voltou. <b>Depois das duplicatas</b>, e a
 *       ordem tem motivo: se a mesma compra entrou por duas fontes, o estorno
 *       casaria com a cópia tanto quanto com a original, e o par sairia
 *       errado. Com a duplicata já marcada, sobra uma compra só;</li>
 *   <li>recorrência — <b>por último</b>, para o detector enxergar as marcas.
 *       Rodando antes, um Pix para si mesmo vira "despesa mensal" e
 *       "receita mensal" ao mesmo tempo, e a previsão de saldo projeta as
 *       duas.</li>
 * </ol>
 *
 * <p>Tudo aqui é idempotente: rodar de novo não desmarca nada e não pareia o
 * que já foi pareado. E tudo é reversível pelo app — a marca some com um toque.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatementHygieneService {

    private final InternalTransferService internalTransferService;
    private final InvestmentFlowService investmentFlowService;
    private final FamilyTransferService familyTransferService;
    private final DuplicateTransactionService duplicateService;
    private final RefundReconciliationService refundService;
    private final RecurrenceDetectionService recurrenceDetectionService;
    private final WatchmanService watchmanService;
    private final UserRepository userRepository;

    public Outcome runFor(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
        return runFor(user.getEmail());
    }

    /**
     * Registra a passada de um vigia (EC-202).
     *
     * <p>Best-effort de propósito: a faxina é o trabalho, o recado é a
     * prestação de contas dele. Se o registro falhar, os números do usuário
     * já estão certos e derrubar a importação inteira por causa do diário
     * seria trocar o essencial pelo acessório — mas o erro sobe no log,
     * porque um diário que falha calado é pior do que nenhum.
     */
    private void anota(User user, SweepRun.Kind kind, int afetados,
                       java.math.BigDecimal volume, java.util.Collection<UUID> tocadas) {
        try {
            watchmanService.record(user, kind, afetados, volume, tocadas);
        } catch (RuntimeException e) {
            log.warn("Recado do vigia {} não pôde ser gravado ({})", kind,
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Roda as cinco etapas em sequência. Cada uma é independente da anterior no
     * sentido de que uma falha não desfaz o que já foi feito — mas nenhuma é
     * pulada silenciosamente: o que quebrar sobe, porque uma faxina que falha
     * sem avisar deixa o número errado na tela do dono parecendo certo.
     */
    public Outcome runFor(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));

        InternalTransferService.Outcome proprias = internalTransferService.reconcileByOwnName(email);
        anota(user, SweepRun.Kind.INTERNAL_TRANSFER, proprias.marked(), null, proprias.markedIds());

        InvestmentFlowService.Outcome investimentos = investmentFlowService.sweep(email, false);
        anota(user, SweepRun.Kind.INVESTMENT_FLOW, investimentos.marked(),
                investimentos.applied().add(investimentos.redeemed()),
                investimentos.details().stream()
                        .map(InvestmentFlowService.Move::transactionId).toList());

        FamilyTransferService.Outcome casa = familyTransferService.reconcile(email);
        anota(user, SweepRun.Kind.FAMILY_TRANSFER, casa.marked(), null, casa.markedIds());

        DuplicateTransactionService.Outcome duplicatas = duplicateService.sweep(email, false);
        anota(user, SweepRun.Kind.DUPLICATE, duplicatas.pairs(), duplicatas.volume(),
                duplicatas.discardedIds());

        RefundReconciliationService.Outcome estornos = refundService.sweep(email, false);
        anota(user, SweepRun.Kind.REFUND, estornos.pairs(), estornos.volume(),
                estornos.details().stream()
                        .flatMap(par -> java.util.stream.Stream.of(par.purchaseId(), par.refundId()))
                        .toList());

        RecurrenceDetectionService.DetectionSummary recorrencia =
                recurrenceDetectionService.detect(email);
        // A recorrência não marca linha: cria séries, que têm o próprio
        // descarte na tela de Recorrências. Por isso entra sem ids
        anota(user, SweepRun.Kind.RECURRENCE,
                recorrencia.seriesCreated() + recorrencia.seriesUpdated(), null, java.util.List.of());

        Outcome resultado = new Outcome(
                proprias.marked(), investimentos.marked(), casa.marked(), duplicatas.pairs(),
                estornos.pairs(), recorrencia.seriesCreated(), recorrencia.seriesUpdated());
        log.info("Faxina pós-importação: {} própria(s), {} de investimento, {} da casa, "
                        + "{} duplicata(s), {} estorno(s), {} série(s) nova(s), {} atualizada(s), user={}",
                resultado.internalMarked(), resultado.investmentMarked(), resultado.familyMarked(),
                resultado.duplicatesMarked(), resultado.refundsMarked(), resultado.seriesCreated(),
                resultado.seriesUpdated(), email);
        return resultado;
    }

    /**
     * @param internalMarked   linhas que passaram a contar como dinheiro do próprio titular
     * @param investmentMarked linhas de aplicação e resgate, que não são gasto nem receita
     * @param familyMarked     linhas que saíram da soma da casa
     * @param duplicatesMarked pares que entraram por duas fontes
     * @param refundsMarked    pares compra + estorno que se anulam
     * @param seriesCreated    séries de recorrência novas
     * @param seriesUpdated    séries que mudaram de valor, cadência ou dia
     */
    public record Outcome(int internalMarked, int investmentMarked, int familyMarked,
                          int duplicatesMarked, int refundsMarked, int seriesCreated,
                          int seriesUpdated) {
    }
}
