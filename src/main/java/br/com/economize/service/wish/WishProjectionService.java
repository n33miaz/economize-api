package br.com.economize.service.wish;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.model.IncomeSource;
import br.com.economize.model.Wish;
import br.com.economize.model.WorkProfile;
import br.com.economize.repository.IncomeSourceRepository;
import br.com.economize.repository.WorkProfileRepository;
import br.com.economize.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Traduz preço em tempo de vida (EC-140).
 *
 * <p>A conta que ninguém faz: uma moto de R$ 18.000 não custa "dezoito mil",
 * custa <b>sete meses do seu ano</b>. É essa tradução que torna o preço
 * sensível, e é por isso que a funcionalidade existe.
 *
 * <h2>Por que o VR não entra no valor da hora</h2>
 * Vale-refeição e vale-alimentação são compensação por trabalho, sim — mas são
 * <b>dinheiro carimbado</b>: ninguém compra uma moto com VR. O desejo é pago
 * com dinheiro livre, então só salário e adiantamento formam a base da hora.
 * Incluir o VR inflaria a renda, baratearia o desejo em horas e daria à pessoa
 * uma resposta que a realidade não honra.
 *
 * <h2>Por que a sobra é mediana, e não média</h2>
 * Um único ciclo atípico — o pneu que furou, o dentista — arrasta a média para
 * baixo e faz todo desejo parecer inalcançável. A mediana ignora o extremo e
 * responde o que o mês TÍPICO permite, que é a pergunta real.
 *
 * <h2>Por que só ciclos completos e com dados</h2>
 * O ciclo corrente tem três dias de gasto e o salário inteiro: contá-lo mostraria
 * uma sobra fantástica que evapora no dia 30. E ciclo sem nenhuma transação não é
 * "mês em que sobrou tudo", é mês sem extrato importado — incluí-lo como zero
 * puxaria a mediana para um número que nunca aconteceu.
 */
@Service
@RequiredArgsConstructor
public class WishProjectionService {

    /** 52 semanas / 12 meses — a mesma constante da previsão de saldo. */
    private static final BigDecimal WEEKS_PER_MONTH =
            BigDecimal.valueOf(52).divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP);

    /** Meio ano de ciclos: recente o bastante para refletir a vida de hoje. */
    static final int CYCLES_SAMPLED = 6;

    /** Teto de parcelas: acima disso não é parcelamento, é mudança de vida. */
    private static final int MAX_INSTALLMENTS = 120;

    /** Cortes simulados, em % da despesa típica. */
    private static final int[] WHAT_IF_CUTS = {5, 10, 20};

    /** Só o dinheiro que se pode gastar em qualquer coisa forma a hora. */
    private static final EnumSet<IncomeSource.Kind> SPENDABLE_WORK_INCOME =
            EnumSet.of(IncomeSource.Kind.SALARY, IncomeSource.Kind.ADVANCE);

    private final IncomeSourceRepository incomeSourceRepository;
    private final WorkProfileRepository workProfileRepository;
    private final AnalyticsService analyticsService;

    public WishBaseline baselineFor(UUID userId) {
        return baselineFor(userId, today());
    }

    /**
     * Sobrecarga com "hoje" explícito: é o que torna o recorte de ciclos
     * testável sem congelar relógio, o mesmo arranjo da previsão de saldo.
     */
    WishBaseline baselineFor(UUID userId, LocalDate today) {
        List<String> gaps = new ArrayList<>();

        List<IncomeSource> sources = incomeSourceRepository.findAllByUserIdAndActiveTrue(userId);
        BigDecimal workIncome = BigDecimal.ZERO;
        for (IncomeSource source : sources) {
            if (source.isConfirmed()
                    && SPENDABLE_WORK_INCOME.contains(source.getKind())
                    && source.getExpectedAmount() != null) {
                workIncome = workIncome.add(source.getExpectedAmount());
            }
        }
        if (workIncome.signum() <= 0) gaps.add(WishBaseline.GAP_CONFIRMED_INCOME);

        WorkProfile profile = workProfileRepository.findById(userId).orElse(null);
        BigDecimal hoursPerMonth = null;
        BigDecimal hoursPerDay = null;
        if (profile == null) {
            gaps.add(WishBaseline.GAP_WORK_PROFILE);
        } else {
            hoursPerDay = profile.getHoursPerDay();
            hoursPerMonth = hoursPerDay
                    .multiply(BigDecimal.valueOf(profile.getDaysPerWeek()))
                    .multiply(WEEKS_PER_MONTH)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal hourlyRate = null;
        if (workIncome.signum() > 0 && hoursPerMonth != null && hoursPerMonth.signum() > 0) {
            hourlyRate = workIncome.divide(hoursPerMonth, 2, RoundingMode.HALF_UP);
        }

        // A âncora do salário, quando existe, recorta os ciclos do jeito que o
        // usuário vive o mês (EC-135) — e não do jeito do calendário
        Short anchorDay = sources.stream()
                .filter(s -> s.getKind() == IncomeSource.Kind.SALARY && s.getAnchorDay() != null)
                .map(IncomeSource::getAnchorDay)
                .findFirst()
                .orElse(null);

        List<BigDecimal> leftovers = new ArrayList<>();
        List<BigDecimal> expenses = new ArrayList<>();
        for (AnalysisWindow window : completedCycles(anchorDay, CYCLES_SAMPLED, today)) {
            AnalyticsService.CycleNet net = analyticsService.netFor(userId, window);
            // ciclo sem extrato importado não é ciclo em que nada aconteceu
            if (net.income().signum() == 0 && net.expense().signum() == 0) continue;
            leftovers.add(net.leftover());
            expenses.add(net.expense());
        }

        BigDecimal monthlyLeftover = median(leftovers);
        BigDecimal monthlyExpense = median(expenses);
        if (leftovers.isEmpty()) {
            gaps.add(WishBaseline.GAP_HISTORY);
        } else if (monthlyLeftover.signum() <= 0) {
            gaps.add(WishBaseline.GAP_NO_LEFTOVER);
        }

        return new WishBaseline(
                scale2(workIncome), hourlyRate, hoursPerMonth, hoursPerDay,
                monthlyLeftover, monthlyExpense, leftovers.size(), List.copyOf(gaps));
    }

    public WishProjection project(Wish wish, WishBaseline baseline) {
        return project(wish, baseline, today());
    }

    WishProjection project(Wish wish, WishBaseline baseline, LocalDate today) {
        BigDecimal target = wish.getTargetAmount();
        BigDecimal saved = wish.getSavedAmount() != null ? wish.getSavedAmount() : BigDecimal.ZERO;
        BigDecimal remaining = target.subtract(saved).max(BigDecimal.ZERO);
        boolean achieved = remaining.signum() == 0;

        // Divide-se pelo valor CHEIO, e não pela hora já arredondada: arredondar
        // antes de dividir desloca o resultado em horas inteiras num desejo caro
        BigDecimal hoursOfWork = null;
        BigDecimal workDays = null;
        BigDecimal workMonths = null;
        BigDecimal workYears = null;
        if (baseline.knowsHourlyRate() && baseline.hoursPerMonth() != null
                && baseline.workIncome().signum() > 0) {
            hoursOfWork = remaining
                    .multiply(baseline.hoursPerMonth())
                    .divide(baseline.workIncome(), 1, RoundingMode.HALF_UP);
            if (baseline.hoursPerDay() != null && baseline.hoursPerDay().signum() > 0) {
                workDays = hoursOfWork.divide(baseline.hoursPerDay(), 1, RoundingMode.HALF_UP);
            }
            // Um mês de trabalho é uma renda mensal inteira, então o mês sai da
            // divisão direta — sem passar pelas horas já arredondadas, que num
            // desejo caro deslocariam o resultado em dias
            workMonths = remaining.divide(baseline.workIncome(), 1, RoundingMode.HALF_UP);
            workYears = remaining.divide(
                    baseline.workIncome().multiply(BigDecimal.valueOf(12)), 1, RoundingMode.HALF_UP);
        }

        Integer months = null;
        LocalDate estimatedDate = null;
        Integer installments = null;
        BigDecimal maxInstallment = null;
        if (!achieved && baseline.knowsLeftover()) {
            months = cyclesToCover(remaining, baseline.monthlyLeftover());
            estimatedDate = months != null ? today.plusMonths(months) : null;
            // Mesmo número, leituras diferentes: guardar até completar, ou levar
            // hoje e pagar com a sobra. Sem juros a matemática é a mesma — o que
            // muda é quando a coisa fica na sua mão, e essa escolha é do usuário
            installments = months;
            maxInstallment = baseline.monthlyLeftover();
        }

        return new WishProjection(
                scale2(remaining), hoursOfWork, workDays, workMonths, workYears,
                months, estimatedDate,
                installments, maxInstallment, achieved,
                whatIfs(remaining, baseline, months, today));
    }

    /**
     * Cenários de corte. Existem sobretudo para quem NÃO tem sobra: é a única
     * resposta útil para "não fecha de jeito nenhum", porque mostra o quanto
     * precisaria mudar para o desejo sair do impossível.
     */
    private List<WishProjection.WhatIf> whatIfs(BigDecimal remaining, WishBaseline baseline,
                                                Integer baseMonths, LocalDate today) {
        List<WishProjection.WhatIf> out = new ArrayList<>();
        BigDecimal expense = baseline.monthlyExpense();
        if (remaining.signum() == 0 || expense == null || expense.signum() <= 0) return out;

        BigDecimal leftover = baseline.monthlyLeftover() != null
                ? baseline.monthlyLeftover() : BigDecimal.ZERO;

        for (int pct : WHAT_IF_CUTS) {
            BigDecimal cut = expense.multiply(BigDecimal.valueOf(pct))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal newLeftover = leftover.add(cut);
            // corte que não tira o mês do vermelho não antecipa nada
            if (newLeftover.signum() <= 0) continue;

            Integer months = cyclesToCover(remaining, newLeftover);
            if (months == null) continue;
            Integer earlier = baseMonths != null ? baseMonths - months : null;
            // cenário que não antecipa nada é ruído na tela
            if (earlier != null && earlier <= 0) continue;
            out.add(new WishProjection.WhatIf(pct, cut, months,
                    today.plusMonths(months), earlier));
        }
        return out;
    }

    /** {@code null} quando nem em dez anos fecha — número gigante não informa. */
    private Integer cyclesToCover(BigDecimal remaining, BigDecimal perCycle) {
        if (perCycle == null || perCycle.signum() <= 0) return null;
        int cycles = remaining.divide(perCycle, 0, RoundingMode.CEILING).intValue();
        return cycles > MAX_INSTALLMENTS ? null : Math.max(cycles, 1);
    }

    /**
     * Os últimos ciclos COMPLETOS. Sem âncora são meses do calendário; com ela,
     * o mês do usuário (dia 5 ao dia 4). O ciclo corrente nunca entra.
     */
    List<AnalysisWindow> completedCycles(Short anchorDay, int count, LocalDate today) {
        List<AnalysisWindow> windows = new ArrayList<>();

        if (anchorDay == null) {
            YearMonth current = YearMonth.from(today);
            for (int i = 1; i <= count; i++) {
                windows.add(AnalysisWindow.ofMonth(current.minusMonths(i)));
            }
            return windows;
        }

        // O mês cujo dia-âncora já passou é o do ciclo CORRENTE; os completos
        // vêm antes dele
        YearMonth cursor = YearMonth.from(today);
        if (anchoredOn(cursor, anchorDay).isAfter(today)) {
            cursor = cursor.minusMonths(1);
        }
        for (int i = 1; i <= count; i++) {
            YearMonth month = cursor.minusMonths(i);
            LocalDate start = anchoredOn(month, anchorDay);
            LocalDate end = anchoredOn(month.plusMonths(1), anchorDay).minusDays(1);
            windows.add(AnalysisWindow.of(start, end));
        }
        return windows;
    }

    /** Dia 31 em fevereiro é o último dia de fevereiro, não erro. */
    private static LocalDate anchoredOn(YearMonth month, short anchorDay) {
        return month.atDay(Math.min(anchorDay, month.lengthOfMonth()));
    }

    /**
     * A mediana, não a média — ver o javadoc da classe. Em contagem par é a
     * média dos dois centrais, o que evita eleger arbitrariamente um dos lados.
     */
    static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) return null;
        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.sort(BigDecimal::compareTo);
        int size = sorted.size();
        int mid = size / 2;
        BigDecimal result = size % 2 == 1
                ? sorted.get(mid)
                : sorted.get(mid - 1).add(sorted.get(mid))
                .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        return scale2(result);
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** UTC: é o fuso em que os parsers gravam as datas do extrato. */
    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
