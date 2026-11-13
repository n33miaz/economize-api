package br.com.economize.service;

import br.com.economize.dto.analytics.CommitmentsResponse;
import br.com.economize.dto.analytics.IncomePatternResponse;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.IncomeSource;
import br.com.economize.model.User;
import br.com.economize.repository.IncomeSourceRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.recurrence.RecurringSeriesService;
import br.com.economize.service.statement.category.DebtClassifier;
import br.com.economize.service.wish.IncomePatternService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Parcelas e compromissos dos próximos meses, contra a renda.
 *
 * <p>No Brasil o parcelado sem juros é perto de metade das compras no crédito,
 * e quase ninguém acompanha as parcelas. O Banco Central promete para 2027 um
 * Painel de Dívidas que avisa quando elas passam de 40% da capacidade de
 * pagamento. Este serviço responde a mesma pergunta com o que o app já mede —
 * <b>não recalcula nada</b>:
 * <ul>
 * <li><b>Parcelas</b> vêm da projeção por mês do
 * {@link InstallmentProjectionService} (que já respeita {@code countsInTotals}
 * e estornos de linha inteira).</li>
 * <li><b>Dívidas recorrentes</b> (financiamento, empréstimo, consórcio,
 * rotativo e parcelamento de fatura) vêm dos vencimentos do
 * {@link RecurringSeriesService}, filtrados pelo {@link DebtClassifier}.
 * Parcelamento de até {@link InstallmentProjectionService#MAX_PARCELAS} vezes
 * fica de fora daqui porque já entrou pela projeção — contá-lo nas duas
 * cobraria duas vezes.</li>
 * </ul>
 *
 * <p><b>A fatura cheia não entra.</b> Ela já contém as parcelas, e o resto dela
 * é consumo do mês, que não se sabe de antemão. Somar a fatura prevista em cima
 * das parcelas contaria a mesma compra duas vezes. A parte da fatura que vira
 * dívida (rotativo e parcelamento de fatura) entra pelas recorrências.
 *
 * <p><b>Mês corrente é parcial:</b> conta o que ainda vai cair a partir de hoje.
 * O que já saiu este mês está no extrato, e somá-lo exigiria separar a parcela
 * já paga da projetada — os meses seguintes são cheios.
 */
@Service
public class CommitmentPanelService {

    static final int DEFAULT_MONTHS = 6;
    static final int MAX_MONTHS = 12;

    /** Vale-refeição e vale-alimentação não pagam parcela nem financiamento. */
    private static final Set<IncomeSource.Kind> VOUCHERS =
            Set.of(IncomeSource.Kind.MEAL_VOUCHER, IncomeSource.Kind.FOOD_VOUCHER);

    private static final Set<DebtClassifier.DebtKind> RECURRING_DEBT = Set.of(
            DebtClassifier.DebtKind.FINANCING, DebtClassifier.DebtKind.LOAN,
            DebtClassifier.DebtKind.CONSORTIUM, DebtClassifier.DebtKind.REVOLVING);

    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    private final InstallmentProjectionService installmentProjectionService;
    private final RecurringSeriesService recurringSeriesService;
    private final IncomeSourceRepository incomeSourceRepository;
    private final IncomePatternService incomePatternService;
    private final UserRepository userRepository;
    // "Hoje" na zona financeira (FinancialClockConfig), não na do servidor
    private final Clock clock;
    /** Referência, não regra: o limite do aviso, em %. */
    private final BigDecimal defaultThresholdPct;

    public CommitmentPanelService(InstallmentProjectionService installmentProjectionService,
                                  RecurringSeriesService recurringSeriesService,
                                  IncomeSourceRepository incomeSourceRepository,
                                  IncomePatternService incomePatternService,
                                  UserRepository userRepository,
                                  Clock clock,
                                  @Value("${economize.commitments.threshold-pct:40}") BigDecimal defaultThresholdPct) {
        this.installmentProjectionService = installmentProjectionService;
        this.recurringSeriesService = recurringSeriesService;
        this.incomeSourceRepository = incomeSourceRepository;
        this.incomePatternService = incomePatternService;
        this.userRepository = userRepository;
        this.clock = clock;
        this.defaultThresholdPct = defaultThresholdPct;
    }

    /**
     * @param months       quantos meses, do corrente em diante (1 a 12; nulo = 6)
     * @param thresholdPct limite do aviso em % (acima de 0 e até 100; nulo = o
     *                     configurado)
     */
    // Leitura numa sessão só, como os vizinhos (DebtInsightService,
    // CommittedIncomeService): WebFlux não mantém sessão aberta entre consultas
    @Transactional(readOnly = true)
    public CommitmentsResponse panel(String email, Integer months, BigDecimal thresholdPct) {
        int horizonte = months != null ? months : DEFAULT_MONTHS;
        if (horizonte < 1 || horizonte > MAX_MONTHS) {
            throw new IllegalArgumentException(String.format(
                    "Janela inválida: months deve estar entre 1 e %d (recebido: %d)",
                    MAX_MONTHS, horizonte));
        }
        BigDecimal limite = thresholdPct != null ? thresholdPct : defaultThresholdPct;
        if (limite.signum() <= 0 || limite.compareTo(CEM) > 0) {
            throw new IllegalArgumentException(
                    "Limite inválido: thresholdPct deve ser maior que 0 e no máximo 100");
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
        return panel(user, LocalDate.now(clock), horizonte, limite);
    }

    /** Sobrecarga com "hoje" explícito — é o que torna o recorte testável. */
    CommitmentsResponse panel(User user, LocalDate today, int horizonte, BigDecimal limite) {
        YearMonth atual = YearMonth.from(today);
        YearMonth ultimo = atual.plusMonths(horizonte - 1L);

        Map<YearMonth, BigDecimal> parcelas = new HashMap<>();
        // Empréstimo ou financiamento curto com "3/12" na descrição já entra pela
        // projeção de parcelas; o mesmo boleto, visto como recorrência, não pode
        // entrar de novo. Guarda-se o valor por mês para reconhecê-lo abaixo
        Map<YearMonth, List<BigDecimal>> dividaProjetada = new HashMap<>();
        for (InstallmentProjectionService.Series serie :
                installmentProjectionService.overviewFor(user.getEmail(), atual).series()) {
            if (serie.finished() || serie.remaining() <= 0) continue;
            // A última vista é `remaining` meses antes da última: daí em diante,
            // uma parcela por mês (a projeção é por mês, não por dia)
            YearMonth vista = YearMonth.parse(serie.lastMonth()).minusMonths(serie.remaining());
            for (int i = 1; i <= serie.remaining(); i++) {
                YearMonth mes = vista.plusMonths(i);
                // Mês já passado sem a parcela no extrato não é "a vencer":
                // provavelmente caiu e ainda não foi importada
                if (mes.isBefore(atual) || mes.isAfter(ultimo)) continue;
                parcelas.merge(mes, serie.installmentAmount(), BigDecimal::add);
                if (DebtClassifier.classify(serie.description()).kind()
                        != DebtClassifier.DebtKind.INSTALLMENT) {
                    dividaProjetada.computeIfAbsent(mes, k -> new ArrayList<>())
                            .add(serie.installmentAmount());
                }
            }
        }

        Map<YearMonth, BigDecimal> dividas = new HashMap<>();
        for (RecurringSeriesService.UpcomingDue due : recurringSeriesService
                .upcomingExpenses(user.getId(), today, ultimo.atEndOfMonth())) {
            if (!isRecurringDebt(due.name())) continue;
            YearMonth mes = YearMonth.from(due.dueDate());
            if (jaProjetada(dividaProjetada.get(mes), due.amount())) continue;
            dividas.merge(mes, due.amount(), BigDecimal::add);
        }

        CommitmentsResponse.CommitmentIncome renda = income(user);
        BigDecimal valorRenda = renda.amount();

        List<CommitmentsResponse.CommitmentMonth> meses = new ArrayList<>();
        BigDecimal pico = null;
        boolean passou = false;
        for (YearMonth mes = atual; !mes.isAfter(ultimo); mes = mes.plusMonths(1)) {
            BigDecimal p = scale2(parcelas.getOrDefault(mes, BigDecimal.ZERO));
            BigDecimal d = scale2(dividas.getOrDefault(mes, BigDecimal.ZERO));
            BigDecimal total = p.add(d);
            BigDecimal share = valorRenda != null
                    ? total.multiply(CEM).divide(valorRenda, 1, RoundingMode.HALF_UP)
                    : null;
            // "Passar de" 40% é estritamente acima: exatamente no limite não avisa
            boolean acima = share != null && share.compareTo(limite) > 0;
            if (share != null && (pico == null || share.compareTo(pico) > 0)) pico = share;
            passou |= acima;
            meses.add(new CommitmentsResponse.CommitmentMonth(
                    mes.toString(), mes.equals(atual), p, d, total, share, acima));
        }

        return new CommitmentsResponse(limite, renda, meses, pico, passou);
    }

    /**
     * Renda mensal considerada. Só fonte <b>confirmada</b>: confirmar quanto se
     * ganha é decisão de quem ganha, e sugestão do extrato não basta.
     *
     * <p>Primeiro o valor cadastrado. Fonte cadastrada sem valor usa o padrão
     * medido no extrato — e só então o {@link IncomePatternService}, que é
     * caro, é chamado. Sem nada disso a renda é nula e o percentual não sai:
     * dividir por um chute faria a pessoa confiar num número que ninguém mediu.
     */
    private CommitmentsResponse.CommitmentIncome income(User user) {
        List<IncomeSource> fontes = incomeSourceRepository.findAllByUserIdAndActiveTrue(user.getId())
                .stream()
                .filter(IncomeSource::isConfirmed)
                .filter(f -> !VOUCHERS.contains(f.getKind()))
                .toList();

        BigDecimal soma = BigDecimal.ZERO;
        int usadas = 0;
        List<UUID> semValor = new ArrayList<>();
        for (IncomeSource fonte : fontes) {
            if (fonte.getExpectedAmount() != null && fonte.getExpectedAmount().signum() > 0) {
                soma = soma.add(fonte.getExpectedAmount());
                usadas++;
            } else {
                semValor.add(fonte.getId());
            }
        }

        boolean medida = false;
        if (!semValor.isEmpty()) {
            Map<UUID, BigDecimal> medidos = new LinkedHashMap<>();
            for (IncomePatternResponse.SourcePattern sp :
                    incomePatternService.analyze(user.getEmail()).sources()) {
                if (sp.pattern() == null || sp.pattern().expectedAmount() == null) continue;
                medidos.put(sp.incomeSourceId(), sp.pattern().expectedAmount());
            }
            for (UUID id : semValor) {
                BigDecimal valor = medidos.get(id);
                if (valor == null || valor.signum() <= 0) continue;
                soma = soma.add(valor);
                usadas++;
                medida = true;
            }
        }

        if (usadas == 0) {
            return new CommitmentsResponse.CommitmentIncome(null, "NONE", 0);
        }
        return new CommitmentsResponse.CommitmentIncome(
                scale2(soma), medida ? "MEASURED" : "SOURCES", usadas);
    }

    /**
     * A recorrência é dívida? Parcelamento curto fica de fora porque a projeção
     * de parcelas já o contou; o longo (financiamento em 48x) só aparece aqui.
     */
    static boolean isRecurringDebt(String name) {
        DebtClassifier.DebtSignal sinal = DebtClassifier.classify(name);
        if (RECURRING_DEBT.contains(sinal.kind())) return true;
        return sinal.kind() == DebtClassifier.DebtKind.INSTALLMENT
                && sinal.total() != null
                && sinal.total() > InstallmentProjectionService.MAX_PARCELAS;
    }

    /**
     * A recorrência é a mesma dívida que a projeção já contou no mês? Mesmo
     * valor, com folga de 2% (mínimo R$ 1,00) para juros e arredondamento.
     * Cada valor projetado casa com uma recorrência só.
     */
    private static boolean jaProjetada(List<BigDecimal> projetadas, BigDecimal valor) {
        if (projetadas == null || valor == null) return false;
        for (int i = 0; i < projetadas.size(); i++) {
            BigDecimal folga = projetadas.get(i).multiply(new BigDecimal("0.02")).max(BigDecimal.ONE);
            if (projetadas.get(i).subtract(valor).abs().compareTo(folga) <= 0) {
                projetadas.remove(i);
                return true;
            }
        }
        return false;
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
