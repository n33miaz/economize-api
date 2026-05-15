package br.com.painel_economico.service;

import br.com.painel_economico.model.BankTransaction;
import br.com.painel_economico.model.Report;
import br.com.painel_economico.model.User;
import br.com.painel_economico.repository.BankTransactionRepository;
import br.com.painel_economico.repository.ReportRepository;
import br.com.painel_economico.repository.UserRepository;
import br.com.painel_economico.service.event.DomainEventPublisher;
import br.com.painel_economico.service.event.ReportGeneratedEvent;
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

        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        for (BankTransaction tx : transactions) {
            String cat = tx.getCategory() != null ? tx.getCategory() : "OTHER";
            byCategory.merge(cat, tx.getAmount(), BigDecimal::add);
        }

        String dominant = byCategory.entrySet().stream()
                .max(Comparator.comparing(entry -> entry.getValue().abs()))
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

    public Report detail(UUID id) {
        return reportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Relatório não encontrado"));
    }

    public void delete(UUID id) {
        reportRepository.deleteById(id);
    }

    private String buildSummary(Report.Period period, BigDecimal income, BigDecimal expense, String dominant) {
        BigDecimal saldo = income.subtract(expense);
        String tone = saldo.signum() >= 0 ? "positivo" : "negativo";
        return String.format(
                "Período %s: receitas R$ %s, despesas R$ %s, saldo %s (R$ %s). Categoria dominante: %s.",
                period.name().toLowerCase(),
                income.toPlainString(),
                expense.toPlainString(),
                tone,
                saldo.toPlainString(),
                dominant != null ? dominant : "n/d");
    }

    private String serialize(Map<String, BigDecimal> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("Falha ao serializar categorias", e);
            return "{}";
        }
    }
}
