package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.recurrence.MerchantKeyExtractor;
import br.com.economize.service.statement.category.DebtClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Quantas parcelas faltam, e até quando — EC-213 e EC-217.
 *
 * <p><b>O defeito no concorrente.</b> No tour do Pierre, em 09/09/2026, a tela
 * de parcelamentos afirmava <i>"1 de 3, última em Agosto/2026"</i>. Estávamos
 * em setembro: a última parcela estaria no passado e ainda faltariam duas. É
 * impossível, e o número aparecia com a mesma cara de um número certo.
 *
 * <p><b>A regra, e por que ela é sobre MÊS e não sobre dia.</b> Parcela cai em
 * ciclo de fatura, não em aniversário da compra. A série real do dono prova
 * isso:
 *
 * <table><caption>Mercadolivre*Bwgshop, três parcelas em três faturas</caption>
 *   <tr><td>Parcela 1/3</td><td>09/08/2026</td><td>R$ 199,98</td></tr>
 *   <tr><td>Parcela 2/3</td><td>03/09/2026</td><td>R$ 199,96</td></tr>
 *   <tr><td>Parcela 3/3</td><td>09/10/2026</td><td>R$ 199,96</td></tr>
 * </table>
 *
 * <p>Do dia 9 de agosto para o dia 3 de setembro vão 25 dias; do dia 3 ao dia
 * 9 de outubro, 36. <b>Nenhum intervalo é "um mês".</b> Mas o MÊS avança
 * exatamente um por parcela — agosto, setembro, outubro — porque é isso que
 * uma parcela é: uma por fatura. Projetar dia daria uma data errada com cara
 * de precisa; projetar mês dá a resposta que a pergunta tinha.
 *
 * <p><b>Os dois centavos.</b> A parcela 1 é R$ 199,98 e as outras duas são
 * R$ 199,96 — o arredondamento da divisão sobra na primeira. Uma projeção que
 * multiplique "valor da parcela × quantas faltam" usando a PRIMEIRA erra o
 * total; por isso o estimador é a parcela mais RECENTE que se viu, que é a que
 * as próximas vão repetir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstallmentProjectionService {

    /**
     * Teto de parcelas de uma série que ainda se acredita.
     *
     * <p>O {@link DebtClassifier} já recusa acima de 96. Aqui o teto é sobre o
     * que ENTRA na projeção de compromisso: uma série de 96 meses é
     * financiamento, e somá-la ao "falta pagar" dos parcelamentos de cartão
     * misturaria duas coisas que a pessoa pensa separado.
     */
    static final int MAX_PARCELAS = 36;

    private final BankTransactionRepository bankTransactionRepository;
    private final UserRepository userRepository;

    public Overview overviewFor(String email) {
        return overviewFor(email, YearMonth.now());
    }

    /** @param hoje injetado para o teste mandar no relógio. */
    public Overview overviewFor(String email, YearMonth hoje) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));

        // Chave: estabelecimento + total de parcelas. Duas compras de 3x na
        // mesma loja colidiriam — o desempate vem abaixo, quando o número de
        // parcela se repete dentro do grupo
        Map<String, List<Seen>> grupos = new LinkedHashMap<>();
        for (BankTransaction tx : bankTransactionRepository
                .findAllByUserIdOrderByDateDesc(user.getId())) {
            if (tx.isIgnored() || tx.isRefunded() || tx.isInternalTransfer()) continue;
            if (tx.getAmount().signum() >= 0) continue;
            DebtClassifier.DebtSignal sinal = DebtClassifier.classify(tx.getDescription());
            if (sinal.installment() == null || sinal.total() == null) continue;
            if (sinal.total() < 2 || sinal.total() > MAX_PARCELAS) continue;
            if (sinal.installment() > sinal.total()) continue;

            String chave = MerchantKeyExtractor.deriveKey(tx.getDescription()) + "#" + sinal.total();
            grupos.computeIfAbsent(chave, k -> new ArrayList<>())
                    .add(new Seen(sinal.installment(), sinal.total(),
                            tx.getAmount().abs(), tx.getDate().toLocalDate(),
                            tx.displayDescription()));
        }

        List<Series> series = new ArrayList<>();
        for (Map.Entry<String, List<Seen>> grupo : grupos.entrySet()) {
            series.addAll(separar(grupo.getValue(), hoje));
        }
        series.sort(Comparator.comparing(Series::lastMonth));

        List<Series> abertas = series.stream().filter(s -> !s.finished()).toList();
        BigDecimal aVencer = abertas.stream().map(Series::remainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("Parcelamentos: {} série(s), {} em aberto, R$ {} a vencer, user={}",
                series.size(), abertas.size(), aVencer, user.getId());
        return new Overview(series.size(), abertas.size(), aVencer, series);
    }

    /**
     * Um grupo pode conter DUAS compras diferentes na mesma loja com o mesmo
     * número de parcelas. Quando o mesmo número de parcela aparece duas vezes,
     * é sinal de que são séries distintas — e misturá-las inventaria um
     * parcelamento que ninguém fez.
     */
    private List<Series> separar(List<Seen> vistas, YearMonth hoje) {
        List<List<Seen>> baldes = new ArrayList<>();
        vistas.stream()
                .sorted(Comparator.comparing(Seen::date))
                .forEach(vista -> {
                    for (List<Seen> balde : baldes) {
                        boolean repetida = balde.stream()
                                .anyMatch(s -> s.number() == vista.number());
                        if (!repetida) {
                            balde.add(vista);
                            return;
                        }
                    }
                    baldes.add(new ArrayList<>(List.of(vista)));
                });

        List<Series> resultado = new ArrayList<>();
        for (List<Seen> balde : baldes) {
            resultado.add(montar(balde, hoje));
        }
        return resultado;
    }

    private Series montar(List<Seen> balde, YearMonth hoje) {
        Seen ultima = balde.stream().max(Comparator.comparing(Seen::number)).orElseThrow();
        Seen primeira = balde.stream().min(Comparator.comparing(Seen::number)).orElseThrow();
        int total = ultima.total();
        int faltam = total - ultima.number();

        // O MÊS avança um por parcela — uma por fatura. Projetar dia daria uma
        // data errada com cara de precisa (ver o javadoc da classe)
        YearMonth mesDaUltimaVista = YearMonth.from(ultima.date());
        YearMonth ultimoMes = mesDaUltimaVista.plusMonths(faltam);
        YearMonth primeiroMes = YearMonth.from(primeira.date()).minusMonths(primeira.number() - 1L);

        // Estimador: a parcela mais RECENTE. A primeira carrega o
        // arredondamento da divisão (R$ 199,98 contra R$ 199,96) e
        // multiplicá-la pelo que falta erraria o total
        BigDecimal valorDaParcela = ultima.amount();
        BigDecimal aVencer = valorDaParcela.multiply(BigDecimal.valueOf(faltam));

        return new Series(ultima.description(), total, balde.size(), faltam,
                valorDaParcela, aVencer, primeiroMes.toString(), ultimoMes.toString(),
                faltam == 0 || ultimoMes.isBefore(hoje));
    }

    /** Uma parcela vista no extrato. */
    private record Seen(int number, int total, BigDecimal amount, LocalDate date,
                        String description) {
    }

    /**
     * @param seen           quantas parcelas desta série o extrato mostra — pode
     *                       ser menos que {@code total - remaining} quando o
     *                       histórico começa no meio
     * @param remaining      quantas ainda vão cair
     * @param remainingAmount o que falta pagar, estimado pela parcela mais recente
     * @param lastMonth      `YYYY-MM` da última parcela
     * @param finished       a série já acabou
     */
    public record Series(String description, int total, int seen, int remaining,
                         BigDecimal installmentAmount, BigDecimal remainingAmount,
                         String firstMonth, String lastMonth, boolean finished) {
    }

    /**
     * @param openSeries    quantos parcelamentos ainda vão cobrar
     * @param remainingTotal o total a vencer somando todos eles — o número que
     *                       o EC-213 pede na primeira tela
     */
    public record Overview(int totalSeries, int openSeries, BigDecimal remainingTotal,
                           List<Series> series) {
    }
}
