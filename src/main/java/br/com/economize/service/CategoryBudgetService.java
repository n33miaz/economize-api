package br.com.economize.service;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.CategoryBudget;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryBudgetRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O teto por categoria, e o aviso ao cruzá-lo — EC-204.
 *
 * <p><b>A decisão que sustenta esta classe:</b> um teto é <b>mensal</b>, mas o
 * usuário lê o gasto pelo <b>ciclo</b> dele (o dono ancora o mês no dia 12).
 * Comparar um teto de R$ 800 com o gasto de uma janela de 11 dias e dizer
 * "você está em 20% do limite" é verdade e é inútil — 20% no terceiro dia é
 * ruim, e no vigésimo oitavo é ótimo.
 *
 * <p>Por isso a resposta tem <b>duas</b>: quanto do teto já foi usado, e se o
 * <b>ritmo</b> leva a estourar. São perguntas diferentes e as duas importam.
 * Um app que só responde a primeira avisa tarde; um que só responde a segunda
 * alarma no dia 2.
 *
 * <p>O aviso respeita o piso do EC-212: estourar por R$ 0,50 não é notícia.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryBudgetService {

    private final CategoryBudgetRepository budgetRepository;
    private final CategoryRepository categoryRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final UserRepository userRepository;

    /**
     * Põe ou muda o teto. Upsert, e não insert: o usuário que reajusta o
     * limite de mercado está mudando o mesmo teto, não criando um segundo.
     */
    @Transactional
    public Budget set(String email, UUID categoryId, BigDecimal monthlyLimit) {
        User user = requireUser(email);
        if (monthlyLimit == null || monthlyLimit.signum() <= 0) {
            throw new IllegalArgumentException("O teto precisa ser maior que zero");
        }
        // Categoria do sistema ou do próprio usuário; a de outro dono responde
        // 404, e não 403, para não confirmar que ela existe
        Category categoria = categoryRepository.findVisibleTo(user.getId()).stream()
                .filter(c -> c.getId().equals(categoryId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Categoria não encontrada"));

        CategoryBudget teto = budgetRepository.findByUserIdAndCategoryId(user.getId(), categoryId)
                .orElseGet(() -> CategoryBudget.builder()
                        .user(user).categoryId(categoryId).build());
        teto.setMonthlyLimit(monthlyLimit);
        budgetRepository.save(teto);
        log.info("Teto de categoria definido: {} = R$ {}, user={}",
                categoria.getName(), monthlyLimit, email);
        return new Budget(categoryId, categoria.getName(), monthlyLimit);
    }

    /** Tirar o teto é decisão do usuário e não deixa rastro de aviso. */
    @Transactional
    public void clear(String email, UUID categoryId) {
        User user = requireUser(email);
        budgetRepository.deleteByUserIdAndCategoryId(user.getId(), categoryId);
    }

    @Transactional(readOnly = true)
    public List<Budget> list(String email) {
        User user = requireUser(email);
        Map<UUID, String> nomes = nomesDasCategorias(user.getId());
        return budgetRepository.findAllByUserId(user.getId()).stream()
                .map(teto -> new Budget(teto.getCategoryId(),
                        nomes.get(teto.getCategoryId()), teto.getMonthlyLimit()))
                .sorted(Comparator.comparing(b -> b.categoryName() == null ? "" : b.categoryName()))
                .toList();
    }

    /**
     * Como cada teto está indo dentro da janela.
     *
     * @param window o recorte que o usuário está olhando — pode não ser um mês
     *               inteiro, e é isso que obriga a regra de três
     */
    @Transactional(readOnly = true)
    public Status statusFor(String email, AnalysisWindow window, LocalDate hoje) {
        User user = requireUser(email);
        List<CategoryBudget> tetos = budgetRepository.findAllByUserId(user.getId());
        if (tetos.isEmpty()) return new Status(0, 0, List.of());

        Map<UUID, String> nomes = nomesDasCategorias(user.getId());
        Map<UUID, BigDecimal> gastoPorCategoria = new HashMap<>();
        for (BankTransaction tx : bankTransactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                        user.getId(), window.startInstant(), window.endExclusiveInstant())) {
            // As mesmas exclusões de toda soma do app
            if (tx.isInternalTransfer() || tx.isIgnored() || tx.isRefunded()) continue;
            if (tx.getAmount().signum() >= 0 || tx.getCategoryId() == null) continue;
            gastoPorCategoria.merge(tx.getCategoryId(), tx.getAmount().abs(), BigDecimal::add);
        }

        // Dias já corridos DENTRO da janela: o gasto até hoje só se compara
        // com a fatia do teto que corresponde a esse pedaço do caminho
        long tamanho = Math.max(1, window.lengthInDays());
        long corridos = Math.min(tamanho, Math.max(1,
                java.time.temporal.ChronoUnit.DAYS.between(window.start(), hoje) + 1));

        List<Line> linhas = new ArrayList<>();
        for (CategoryBudget teto : tetos) {
            BigDecimal gasto = gastoPorCategoria.getOrDefault(teto.getCategoryId(), BigDecimal.ZERO);
            BigDecimal limite = teto.getMonthlyLimit();
            // Teto proporcional à janela: um limite mensal contra um ciclo de
            // 45 dias precisa crescer, e contra um de 20 precisa encolher
            BigDecimal limiteDaJanela = limite
                    .multiply(BigDecimal.valueOf(tamanho))
                    .divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);
            BigDecimal esperadoAteHoje = limiteDaJanela
                    .multiply(BigDecimal.valueOf(corridos))
                    .divide(BigDecimal.valueOf(tamanho), 2, RoundingMode.HALF_UP);

            BigDecimal excedente = gasto.subtract(limiteDaJanela);
            boolean estourou = Materiality.vale(excedente) && excedente.signum() > 0;
            // "No ritmo de estourar" é outra pergunta: 20% no terceiro dia é
            // ruim e no vigésimo oitavo é ótimo
            BigDecimal acimaDoRitmo = gasto.subtract(esperadoAteHoje);
            boolean noRitmoDeEstourar = !estourou
                    && Materiality.vale(acimaDoRitmo) && acimaDoRitmo.signum() > 0;

            linhas.add(new Line(teto.getCategoryId(), nomes.get(teto.getCategoryId()),
                    limite, limiteDaJanela, gasto, esperadoAteHoje,
                    estourou ? excedente : BigDecimal.ZERO, estourou, noRitmoDeEstourar));
        }

        linhas.sort(Comparator.comparing(Line::spent).reversed());
        int estourados = (int) linhas.stream().filter(Line::exceeded).count();
        int emRisco = (int) linhas.stream().filter(Line::abovePace).count();
        log.info("Tetos de categoria: {} teto(s), {} estourado(s), {} acima do ritmo, user={}",
                linhas.size(), estourados, emRisco, email);
        return new Status(estourados, emRisco, linhas);
    }

    private Map<UUID, String> nomesDasCategorias(UUID userId) {
        Map<UUID, String> nomes = new HashMap<>();
        categoryRepository.findVisibleTo(userId)
                .forEach(categoria -> nomes.put(categoria.getId(), categoria.getName()));
        return nomes;
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }

    public record Budget(UUID categoryId, String categoryName, BigDecimal monthlyLimit) {
    }

    /**
     * @param windowLimit   o teto mensal esticado (ou encolhido) para o tamanho
     *                      da janela que o usuário está olhando
     * @param expectedSoFar quanto dele já se poderia ter gasto até hoje
     * @param overBy        quanto passou; zero quando não passou
     * @param exceeded      já estourou
     * @param abovePace     ainda não estourou, mas o ritmo leva lá
     */
    public record Line(UUID categoryId, String categoryName, BigDecimal monthlyLimit,
                       BigDecimal windowLimit, BigDecimal spent, BigDecimal expectedSoFar,
                       BigDecimal overBy, boolean exceeded, boolean abovePace) {
    }

    public record Status(int exceededCount, int abovePaceCount, List<Line> lines) {
    }
}
