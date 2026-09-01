package br.com.economize.service.wish;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.analytics.CycleCaveat;
import br.com.economize.model.IncomeSource;
import br.com.economize.repository.IncomeSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * As ressalvas do período (EC-138) — o que o número do mês <b>não</b> diz.
 *
 * <p>Um resumo honesto precisa avisar o que não é comparável. Três coisas
 * distorcem a leitura e nenhuma delas aparece no total:
 *
 * <ol>
 * <li><b>Dinheiro que chegou no fim do ciclo.</b> O VR cai dia 25 e o ciclo
 * fecha dia 31: o que for gasto com ele pertence economicamente ao mês
 * seguinte. Sem a ressalva, o mês que está fechando parece mais caro do que
 * foi — e o seguinte, mais barato.</li>
 * <li><b>Ciclo que ainda não fechou.</b> Comparar 12 dias corridos com um mês
 * inteiro anterior produz uma queda de gasto que ninguém conquistou.</li>
 * <li><b>Período anterior sem dado.</b> A variação contra o nada é sempre
 * espetacular e sempre falsa.</li>
 * </ol>
 *
 * <p>Nenhuma delas é estimativa: todas saem de datas que o sistema já conhece.
 */
@Service
@RequiredArgsConstructor
public class CycleCaveatService {

    /**
     * Quantos dias antes do fim do ciclo já contam como "chegou no fim". Uma
     * semana é o intervalo em que o dinheiro tipicamente ainda não foi usado
     * quando o período fecha — e é curto o bastante para a ressalva não
     * aparecer todo mês, o que a tornaria ruído.
     */
    private static final int DIAS_DE_FIM_DE_CICLO = 7;

    /** "25/07" e nunca "25/7": data sem zero à esquerda lê como rascunho. */
    private static final java.time.format.DateTimeFormatter DIA_MES =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM");

    private final IncomeSourceRepository incomeSourceRepository;

    /**
     * @param previousHasData se o período comparável teve qualquer movimento
     * @param today           injetado para o teste poder fixar o "hoje" sem
     *                        relógio de sistema
     */
    public List<CycleCaveat> caveatsFor(UUID userId, AnalysisWindow window,
                                        boolean previousHasData, LocalDate today) {
        List<CycleCaveat> ressalvas = new ArrayList<>();

        // 1) o ciclo ainda está aberto — o total é parcial por definição
        if (!window.end().isBefore(today)) {
            long decorridos = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(window.start(), today) + 1);
            long totais = window.lengthInDays();
            if (decorridos < totais) {
                ressalvas.add(new CycleCaveat(
                        CycleCaveat.Kind.PARTIAL_PERIOD,
                        "Este ciclo ainda não fechou",
                        "Você está vendo " + decorridos + " de " + totais
                                + " dias. Comparar com um período inteiro faz o gasto parecer menor do que vai ser.",
                        null));
            }
        }

        // 2) renda que caiu perto do fim: o gasto dela é do próximo ciclo
        for (IncomeSource fonte : incomeSourceRepository.findAllByUserIdAndActiveTrue(userId)) {
            if (fonte.getAnchorDay() == null) continue;
            LocalDate queda = quedaDentroDa(window, fonte.getAnchorDay());
            if (queda == null) continue;
            long faltando = java.time.temporal.ChronoUnit.DAYS.between(queda, window.end());
            if (faltando >= DIAS_DE_FIM_DE_CICLO) continue;

            ressalvas.add(new CycleCaveat(
                    CycleCaveat.Kind.LATE_INCOME,
                    fonte.getName() + " caiu no fim do ciclo",
                    "Entrou em " + DIA_MES.format(queda)
                            + ", a " + (faltando == 0 ? "menos de um dia" : faltando + " dia"
                            + (faltando == 1 ? "" : "s")) + " do fechamento. "
                            + "O que for gasto com esse dinheiro pertence ao próximo mês, não a este.",
                    fonte.getExpectedAmount()));
        }

        // 3) sem período anterior, a variação exibida não significa nada
        if (!previousHasData) {
            ressalvas.add(new CycleCaveat(
                    CycleCaveat.Kind.NO_PREVIOUS_DATA,
                    "Não há período anterior para comparar",
                    "As variações contra um período sem dado sempre parecem enormes. "
                            + "Elas só ficam confiáveis a partir do segundo ciclo completo.",
                    null));
        }

        return ressalvas;
    }

    /**
     * A data em que uma fonte com âncora no dia {@code anchorDay} cai DENTRO da
     * janela, ou {@code null} se não cai.
     *
     * <p>O mês pode não ter o dia da âncora (dia 31 em fevereiro): nesse caso a
     * queda é o último dia do mês, que é onde o pagamento realmente acontece.
     */
    static LocalDate quedaDentroDa(AnalysisWindow window, short anchorDay) {
        // a janela nunca passa de 366 dias, então dois meses candidatos bastam
        // para cobrir qualquer recorte mensal — e o laço protege os maiores
        LocalDate cursor = window.start().withDayOfMonth(1);
        while (!cursor.isAfter(window.end())) {
            int dia = Math.min(anchorDay, cursor.lengthOfMonth());
            LocalDate candidato = cursor.withDayOfMonth(dia);
            if (!candidato.isBefore(window.start()) && !candidato.isAfter(window.end())) {
                return candidato;
            }
            cursor = cursor.plusMonths(1);
        }
        return null;
    }
}
