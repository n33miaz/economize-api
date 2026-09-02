package br.com.economize.service;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.Report;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.ReportRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.event.DomainEventPublisher;
import br.com.economize.service.event.ReportGeneratedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final UserRepository userRepository;
    private final DomainEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Report generate(String email, Report.Period period, OffsetDateTime start, OffsetDateTime end) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));

        var transactions = bankTransactionRepository.findAllByUserIdOrderByDateDesc(user.getId()).stream()
                .filter(tx -> !tx.getDate().isBefore(start) && !tx.getDate().isAfter(end))
                .toList();

        BigDecimal income = transactions.stream()
                .map(BankTransaction::getAmount)
                .filter(amount -> amount.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expense = transactions.stream()
                .map(BankTransaction::getAmount)
                .filter(amount -> amount.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .abs();

        // Entrada e saída SEPARADAS por categoria. Antes as duas somavam na
        // mesma chave e viravam um líquido: uma categoria que recebeu 4.400 e
        // gastou 5.830 aparecia como 1.430, então a fatia da pizza contradizia
        // o total de saídas do próprio relatório na mesma tela
        Map<String, CategorySplit> byCategory = new LinkedHashMap<>();
        for (BankTransaction tx : transactions) {
            String cat = tx.getCategory() != null ? tx.getCategory() : "OTHER";
            byCategory.computeIfAbsent(cat, k -> new CategorySplit()).add(tx.getAmount());
        }

        // A dominante é a de maior GASTO: é o que a pergunta "no que foi meu
        // dinheiro" quer saber. Só quando o período não teve saída nenhuma ela
        // passa a ser a maior entrada
        String dominant = byCategory.entrySet().stream()
                .filter(entry -> entry.getValue().expense.signum() > 0)
                .max(Comparator.comparing(entry -> entry.getValue().expense))
                .or(() -> byCategory.entrySet().stream()
                        .max(Comparator.comparing(entry -> entry.getValue().income)))
                .map(Map.Entry::getKey)
                .orElse(null);

        Report report = Report.builder()
                .user(user)
                .period(period)
                .startDate(start)
                .endDate(end)
                .totalIncome(income)
                .totalExpense(expense)
                .dominantCategory(dominant)
                .summary(buildSummary(period, income, expense, dominant))
                .categoriesJson(serialize(byCategory))
                .build();
        reportRepository.save(report);
        eventPublisher.publish(new ReportGeneratedEvent(user.getId(), period.name(), report.getId()));
        return report;
    }

    public Page<Report> list(String email, Report.Period period, int page, int size) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        PageRequest pageable = PageRequest.of(page, size);
        return period == null
                ? reportRepository.findByUserIdOrderByStartDateDesc(user.getId(), pageable)
                : reportRepository.findByUserIdAndPeriodOrderByStartDateDesc(user.getId(), period, pageable);
    }

    // busca sempre amarrada ao dono do token — relatório é dado privado
    public Report detail(String email, UUID id) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        return reportRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Relatório não encontrado"));
    }

    public void delete(String email, UUID id) {
        Report report = detail(email, id);
        reportRepository.delete(report);
    }

    /**
     * O resumo que aparece no card, em português de gente.
     *
     * <p>A versão anterior imprimia o enum cru e o valor do banco: "Período
     * monthly: receitas R$ 4400.0000". Quatro casas decimais e um enum em
     * inglês na frase mais visível da tela — que o usuário lê antes de qualquer
     * número — faziam o relatório parecer log de depuração.
     *
     * <p>A categoria dominante fica FORA daqui: ela aparece com nome resolvido
     * no próprio card, e repeti-la em texto significaria imprimir a chave de
     * sistema ("OTHER") que o app sabe traduzir e o serviço não.
     */
    private String buildSummary(Report.Period period, BigDecimal income, BigDecimal expense, String dominant) {
        BigDecimal saldo = income.subtract(expense);
        String entrou = brl(income);
        String saiu = brl(expense);
        if (saldo.signum() > 0) {
            return "Entraram " + entrou + " e saíram " + saiu + ": sobraram " + brl(saldo) + ".";
        }
        if (saldo.signum() < 0) {
            // "Faltaram" e não "saldo negativo": o que aconteceu é que o
            // dinheiro acabou antes, e é assim que a pessoa conta o que viveu
            return "Entraram " + entrou + " e saíram " + saiu + ": faltaram " + brl(saldo.abs()) + ".";
        }
        return "Entrou e saiu exatamente " + entrou + " — o período fechou no zero.";
    }

    /** "R$ 4.400,00" com o separador que o Brasil usa, sem casa sobrando. */
    private String brl(BigDecimal value) {
        return java.text.NumberFormat
                .getCurrencyInstance(new java.util.Locale("pt", "BR"))
                .format(value.setScale(2, java.math.RoundingMode.HALF_UP));
    }

    /**
     * Entrada e saída de UMA categoria no período, cada uma em módulo.
     *
     * <p>Serializado como {@code {"FOOD":{"income":0,"expense":500.00}}}. O app
     * ainda aceita o formato antigo (um número com sinal por categoria), que é
     * o que os relatórios já gravados carregam.
     */
    private static final class CategorySplit {
        private BigDecimal income = BigDecimal.ZERO;
        private BigDecimal expense = BigDecimal.ZERO;

        void add(BigDecimal amount) {
            if (amount == null) return;
            if (amount.signum() >= 0) {
                income = income.add(amount);
            } else {
                expense = expense.add(amount.abs());
            }
        }

        public BigDecimal getIncome() {
            return income;
        }

        public BigDecimal getExpense() {
            return expense;
        }
    }

    private String serialize(Map<String, CategorySplit> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("Falha ao serializar categorias", e);
            return "{}";
        }
    }
}
