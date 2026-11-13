package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.Report;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.ReportRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.event.DomainEventPublisher;
import br.com.economize.service.event.ReportGeneratedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final UserRepository userRepository;
    private final DomainEventPublisher eventPublisher;
    private final CategoryRepository categoryRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Teto da janela de um relatório, em dias. Sem teto, um intervalo de dez
     * anos carregava o histórico inteiro na memória de um container de 512 MB
     * para gravar um único retrato. 366 cobre o relatório anual de ano
     * bissexto; o valor fica em propriedade para subir sem deploy de código.
     * O inicializador vale para os testes de unidade, que não passam pelo Spring.
     */
    @Value("${economize.reports.max-window-days:366}")
    private long maxWindowDays = 366;

    public Report generate(String email, Report.Period period, OffsetDateTime start, OffsetDateTime end) {
        validarJanela(start, end);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));

        // Só a janela vem do banco. Antes vinha o histórico INTEIRO para ser
        // filtrado aqui, o que crescia com a idade da conta e não com o
        // período pedido. A consulta é semiaberta [início, fim) e o relatório
        // sempre incluiu o próprio instante final, então o limite superior anda
        // um microssegundo (a precisão do timestamp no Postgres); o filtro em
        // memória continua como rede para a borda.
        //
        // A regra da Análise (BankTransaction#countsInTotals) e o valor LÍQUIDO:
        // somando o bruto de tudo, a aplicação, a duplicata descartada e o par
        // de estorno entravam nos totais e podiam virar a categoria dominante
        var transactions = bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        user.getId(), start, end.plus(1, ChronoUnit.MICROS))
                .stream()
                .filter(tx -> !tx.getDate().isBefore(start) && !tx.getDate().isAfter(end))
                .filter(BankTransaction::countsInTotals)
                .toList();

        BigDecimal income = transactions.stream()
                .map(BankTransaction::getNetAmount)
                .filter(amount -> amount.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expense = transactions.stream()
                .map(BankTransaction::getNetAmount)
                .filter(amount -> amount.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .abs();

        // Entrada e saída SEPARADAS por categoria. Antes as duas somavam na
        // mesma chave e viravam um líquido: uma categoria que recebeu 4.400 e
        // gastou 5.830 aparecia como 1.430, então a fatia da pizza contradizia
        // o total de saídas do próprio relatório na mesma tela
        //
        // A chave é a categoria RAIZ, resolvida pelo categoryId com a mesma
        // regra da Análise (AnalyticsService.rootIdOf). O campo legado
        // `category` guarda a chave da própria subcategoria: "Delivery" virava
        // fatia separada de "Alimentação" e a dominante do relatório discordava
        // da Análise do mesmo mês. A chave gravada continua sendo a legada
        // (systemKey ou slug) da raiz, que é o que o app já sabe traduzir.
        Map<UUID, Category> catalog = categoryRepository.findVisibleTo(user.getId()).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity(), (a, b) -> a));
        Map<String, CategorySplit> byCategory = new LinkedHashMap<>();
        for (BankTransaction tx : transactions) {
            byCategory.computeIfAbsent(rootKeyOf(tx, catalog), k -> new CategorySplit()).add(tx.getNetAmount());
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

    /**
     * Datas invertidas gravavam um relatório zerado como se o período tivesse
     * sido vazio; agora é 400 dizendo o que está errado.
     */
    private void validarJanela(OffsetDateTime start, OffsetDateTime end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("Informe o início e o fim do período do relatório");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("O início do período deve ser anterior ou igual ao fim");
        }
        if (Duration.between(start, end).toDays() > maxWindowDays) {
            throw new IllegalArgumentException(String.format(
                    "Período longo demais: o relatório cobre no máximo %d dias", maxWindowDays));
        }
    }

    /**
     * Chave legada da categoria RAIZ da transação. Sem categoryId (linha antiga)
     * ou com categoria fora do catálogo visível, vale o campo legado da própria
     * linha, como antes — melhor a chave antiga do que inventar uma.
     */
    private static String rootKeyOf(BankTransaction tx, Map<UUID, Category> catalog) {
        UUID rootId = AnalyticsService.rootIdOf(tx.getCategoryId(), catalog);
        Category root = rootId != null ? catalog.get(rootId) : null;
        if (root != null) return TransactionReviewService.legacyKey(root);
        return tx.getCategory() != null ? tx.getCategory() : "OTHER";
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
                // 404 como o resto da API: de outro usuário ou inexistente é a
                // mesma resposta, para não revelar que o id existe
                .orElseThrow(() -> new ResourceNotFoundException("Relatório não encontrado"));
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
        String entrou = Money.brl(income);
        String saiu = Money.brl(expense);
        if (saldo.signum() > 0) {
            return "Entraram " + entrou + " e saíram " + saiu + ": sobraram " + Money.brl(saldo) + ".";
        }
        if (saldo.signum() < 0) {
            // "Faltaram" e não "saldo negativo": o que aconteceu é que o
            // dinheiro acabou antes, e é assim que a pessoa conta o que viveu
            return "Entraram " + entrou + " e saíram " + saiu + ": faltaram " + Money.brl(saldo.abs()) + ".";
        }
        return "Entrou e saiu exatamente " + entrou + " — o período fechou no zero.";
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
