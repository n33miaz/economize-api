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
 *
 * <p><b>Duas entradas, e a diferença é quanto do extrato cada uma lê.</b>
 * {@link #runFor(String)} é a faxina pedida pelo usuário e varre o histórico
 * inteiro, como sempre. {@link #runFor(UUID, HygieneWindow)} é a de depois da
 * importação: entrou uma linha, e ler ~2.000 lançamentos seis vezes por causa
 * dela era o que este serviço fazia num plano de 512 MB. Na janela:
 *
 * <table><caption>O que cada etapa lê depois de uma importação</caption>
 *   <tr><td>movimentação própria</td><td>só os dias importados</td>
 *       <td>regra linha a linha</td></tr>
 *   <tr><td>aplicação e resgate</td><td>só os dias importados</td>
 *       <td>regra linha a linha</td></tr>
 *   <tr><td>transferência da casa</td><td>só os dias importados</td>
 *       <td>regra linha a linha</td></tr>
 *   <tr><td>duplicatas</td><td>dias importados ± 1</td>
 *       <td>a regra só pareia a um dia de distância</td></tr>
 *   <tr><td>estornos</td><td><b>tudo</b></td>
 *       <td>ver abaixo</td></tr>
 *   <tr><td>recorrência</td><td><b>tudo</b></td>
 *       <td>cadência se mede em meses de histórico, e os agregados da série
 *       são recalculados do zero</td></tr>
 * </table>
 *
 * <p><b>Por que o estorno ficou de fora</b>, apesar de a regra dele ter
 * distância máxima (45 dias quando o crédito nomeia a compra, 3 por valor) e
 * de o teto do abate estar gravado na própria compra. O recorte só é igual à
 * varredura total quando o histórico antigo está assentado, e o do estorno
 * nunca está:
 * <ul>
 *   <li>o crédito neutro de cartão espera a compra por tempo indeterminado e
 *       é reexaminado a cada passada;</li>
 *   <li>o crédito pulado porque a compra citada mais próxima é menor do que
 *       ele pode casar numa passada SEGUINTE, depois que outra consumiu
 *       aquela compra;</li>
 *   <li>o que uma compra já recebeu de abate vale para todos os créditos da
 *       mesma passada, então tirar um crédito antigo do recorte muda o que
 *       sobra para um importado.</li>
 * </ul>
 * Dá para enquadrar com margem de 45 dias, mas não para provar que o
 * resultado é o mesmo — e errar ali é abater dinheiro da compra errada.
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
     * A faxina de depois da importação: só o que entrou e o que está em volta.
     *
     * @param janela as datas que a importação trouxe ou alterou. Nulo cai na
     *               varredura total — sem saber o que entrou, recorte nenhum
     *               é seguro
     */
    public Outcome runFor(UUID userId, HygieneWindow janela) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
        return run(user, janela);
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
        return run(user, null);
    }

    /**
     * As etapas, na ordem do cabeçalho. Com {@code janela} nula cada varredura
     * é chamada exatamente como sempre foi — a faxina pedida à mão não muda.
     * Os recados dos vigias são os mesmos nos dois caminhos: o que se anota é
     * o que a passada marcou, não o quanto ela leu.
     */
    private Outcome run(User user, HygieneWindow janela) {
        String email = user.getEmail();

        InternalTransferService.Outcome proprias = janela == null
                ? internalTransferService.reconcileByOwnName(email)
                : internalTransferService.reconcileByOwnName(email, janela);
        anota(user, SweepRun.Kind.INTERNAL_TRANSFER, proprias.marked(), null, proprias.markedIds());

        InvestmentFlowService.Outcome investimentos = janela == null
                ? investmentFlowService.sweep(email, false)
                : investmentFlowService.sweep(email, false, janela);
        anota(user, SweepRun.Kind.INVESTMENT_FLOW, investimentos.marked(),
                investimentos.applied().add(investimentos.redeemed()),
                investimentos.details().stream()
                        .map(InvestmentFlowService.Move::transactionId).toList());

        FamilyTransferService.Outcome casa = janela == null
                ? familyTransferService.reconcile(email)
                : familyTransferService.reconcile(email, janela);
        anota(user, SweepRun.Kind.FAMILY_TRANSFER, casa.marked(), null, casa.markedIds());

        DuplicateTransactionService.Outcome duplicatas = janela == null
                ? duplicateService.sweep(email, false)
                : duplicateService.sweep(email, false, janela);
        anota(user, SweepRun.Kind.DUPLICATE, duplicatas.pairs(), duplicatas.volume(),
                duplicatas.discardedIds());

        // Sempre o histórico inteiro, com ou sem janela — ver o cabeçalho
        RefundReconciliationService.Outcome estornos = refundService.sweep(email, false);
        anota(user, SweepRun.Kind.REFUND, estornos.pairs(), estornos.volume(),
                // Os parciais entram com as duas pontas: o desfazer precisa
                // achar a COMPRA para zerar o abate (V40), não só o crédito
                java.util.stream.Stream.concat(
                        estornos.details().stream()
                                .flatMap(par -> java.util.stream.Stream.of(par.purchaseId(), par.refundId())),
                        estornos.partials().stream()
                                .flatMap(p -> java.util.stream.Stream.of(p.purchaseId(), p.refundId())))
                        .distinct()
                        .toList());

        // Idem: a cadência precisa de meses de histórico
        RecurrenceDetectionService.DetectionSummary recorrencia =
                recurrenceDetectionService.detect(email);
        // A recorrência não marca linha: cria séries, que têm o próprio
        // descarte na tela de Recorrências. Por isso entra sem ids
        anota(user, SweepRun.Kind.RECURRENCE,
                recorrencia.seriesCreated() + recorrencia.seriesUpdated(), null, java.util.List.of());

        Outcome resultado = new Outcome(
                proprias.marked(), investimentos.marked(), casa.marked(), duplicatas.pairs(),
                estornos.pairs(), recorrencia.seriesCreated(), recorrencia.seriesUpdated());
        log.info("Faxina {}: {} própria(s), {} de investimento, {} da casa, "
                        + "{} duplicata(s), {} estorno(s), {} série(s) nova(s), {} atualizada(s), user={}",
                janela == null ? "do histórico inteiro"
                        : "da janela " + janela.earliest() + " a " + janela.latest(),
                resultado.internalMarked(), resultado.investmentMarked(), resultado.familyMarked(),
                resultado.duplicatesMarked(), resultado.refundsMarked(), resultado.seriesCreated(),
                resultado.seriesUpdated(), user.getId());
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
