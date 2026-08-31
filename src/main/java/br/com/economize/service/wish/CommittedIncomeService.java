package br.com.economize.service.wish;

import br.com.economize.dto.wish.WishResponses;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.IncomeSource;
import br.com.economize.model.User;
import br.com.economize.repository.IncomeSourceRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.recurrence.RecurringSeriesService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * "Quando o salário cair, R$ X já têm dono" (EC-136).
 *
 * <p>É a segunda dor concreta do dia a dia: o dinheiro ainda não chegou e uma
 * parte dele já está prometida. Nenhuma tela do app respondia isso — o extrato
 * conta o passado e a previsão de saldo fala em ciclos inteiros, não em "o que
 * sobra de verdade do próximo pagamento".
 *
 * <p><b>Não é previsão estatística.</b> São boletos, assinaturas e faturas que o
 * motor de recorrência já provou no extrato. Um número inventado aqui faria a
 * pessoa gastar o que não tem.
 *
 * <p>A janela é dividida em duas porque as duas perguntas são diferentes:
 * <ul>
 * <li><b>Antes do salário</b> — o que ainda vai sair do que você tem hoje. É a
 * parte aflitiva do fim do mês.</li>
 * <li><b>Depois do salário</b> — o que já está reservado do pagamento que vem, e
 * portanto quanto dele é realmente seu.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CommittedIncomeService {

    /**
     * Sem salário cadastrado não existe "quando cair"; ainda assim a lista de
     * contas próximas é útil, e um mês à frente é o horizonte que a pessoa
     * consegue planejar.
     */
    private static final int FALLBACK_DAYS = 30;

    private final IncomeSourceRepository incomeSourceRepository;
    private final RecurringSeriesService recurringSeriesService;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public WishResponses.CommittedOverview overview(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
        return overview(user.getId(), LocalDate.now(ZoneOffset.UTC));
    }

    /** Sobrecarga com "hoje" explícito — é o que torna o recorte testável. */
    WishResponses.CommittedOverview overview(UUID userId, LocalDate today) {
        List<IncomeSource> sources = incomeSourceRepository.findAllByUserIdAndActiveTrue(userId);

        BigDecimal expectedSalary = BigDecimal.ZERO;
        Short anchorDay = null;
        for (IncomeSource source : sources) {
            if (source.getKind() != IncomeSource.Kind.SALARY || !source.isConfirmed()) continue;
            if (source.getExpectedAmount() != null) {
                expectedSalary = expectedSalary.add(source.getExpectedAmount());
            }
            // Dois salários com âncoras diferentes: manda o que chega primeiro,
            // porque é dele que virão as próximas contas
            if (source.getAnchorDay() != null
                    && (anchorDay == null || nextOccurrence(today, source.getAnchorDay())
                    .isBefore(nextOccurrence(today, anchorDay)))) {
                anchorDay = source.getAnchorDay();
            }
        }

        if (anchorDay == null) {
            // Sem âncora não dá para dizer "quando cair". A lista dos próximos
            // 30 dias continua valendo, e a tela pede o cadastro do salário
            List<WishResponses.CommittedItem> proximos =
                    itemsBetween(userId, today, today.plusDays(FALLBACK_DAYS));
            return new WishResponses.CommittedOverview(
                    false, null, null, null,
                    total(proximos), proximos,
                    BigDecimal.ZERO, List.of(), null);
        }

        LocalDate salaryDate = nextOccurrence(today, anchorDay);
        // O ciclo que este pagamento abre vai até a véspera do próximo
        LocalDate cycleEnd = nextOccurrence(salaryDate.plusDays(1), anchorDay).minusDays(1);

        List<WishResponses.CommittedItem> antes = salaryDate.isAfter(today)
                ? itemsBetween(userId, today, salaryDate.minusDays(1))
                : List.of();
        List<WishResponses.CommittedItem> depois = itemsBetween(userId, salaryDate, cycleEnd);

        BigDecimal comprometidoDepois = total(depois);
        BigDecimal livre = expectedSalary.signum() > 0
                ? expectedSalary.subtract(comprometidoDepois)
                : null;

        return new WishResponses.CommittedOverview(
                true,
                salaryDate,
                (int) ChronoUnit.DAYS.between(today, salaryDate),
                expectedSalary.signum() > 0 ? scale2(expectedSalary) : null,
                total(antes), antes,
                comprometidoDepois, depois,
                livre != null ? scale2(livre) : null);
    }

    private List<WishResponses.CommittedItem> itemsBetween(UUID userId, LocalDate from, LocalDate to) {
        return recurringSeriesService.upcomingExpenses(userId, from, to).stream()
                .map(due -> new WishResponses.CommittedItem(
                        due.seriesId(), due.name(), due.categoryId(),
                        due.dueDate(), scale2(due.amount()), due.estimated()))
                .toList();
    }

    private static BigDecimal total(List<WishResponses.CommittedItem> items) {
        return scale2(items.stream()
                .map(WishResponses.CommittedItem::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /**
     * A próxima vez que o dia-âncora acontece, contando HOJE como válido: no
     * dia 5, com âncora 5, o salário cai hoje — e dizer "faltam 30 dias" seria
     * o oposto da verdade.
     */
    private static LocalDate nextOccurrence(LocalDate from, short anchorDay) {
        YearMonth month = YearMonth.from(from);
        LocalDate candidate = clampToMonth(month, anchorDay);
        if (candidate.isBefore(from)) {
            candidate = clampToMonth(month.plusMonths(1), anchorDay);
        }
        return candidate;
    }

    /** Dia 31 em fevereiro é o último dia de fevereiro, não erro. */
    private static LocalDate clampToMonth(YearMonth month, short anchorDay) {
        return month.atDay(Math.min(anchorDay, month.lengthOfMonth()));
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
