package br.com.economize.service.family;

import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.family.FamilyAnalyticsResponse;
import br.com.economize.dto.family.FamilyTransactionResponse;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.Category;
import br.com.economize.model.FamilyMember;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.FamilyMemberRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A visão compartilhada da casa (EC-149): o que cada membro mostra, somado.
 *
 * <p>A regra que sustenta tudo: <b>uma consulta por membro, com os filtros
 * DELE na cláusula</b> ({@link BankTransactionRepository#sumByCategoryShared} e
 * {@link BankTransactionRepository#findSharedInWindow}). Nada é lido inteiro e
 * peneirado depois — o que a pessoa escolheu não mostrar não sai do banco. O
 * total dela na casa nasce dessas mesmas linhas filtradas, nunca do total
 * pessoal: se nascesse, o outro deduziria a categoria oculta pela diferença.
 *
 * <p>O chamador vê a si mesmo por inteiro, sempre — inclusive com escopo NONE.
 * Ele escolheu o que os OUTROS veem dele; não faz sentido esconder dele mesmo.
 *
 * <p>A janela é a do CLIENTE (mês ou start/end, como na análise pessoal): dois
 * membros com âncoras diferentes veem recortes diferentes da mesma casa, e a
 * tela diz o recorte no chip.
 */
@Service
@RequiredArgsConstructor
public class FamilyAnalyticsService {

    private static final String UNCATEGORIZED_NAME = "Sem categoria";

    private final FamilyMemberRepository memberRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public FamilyAnalyticsResponse monthly(String email, AnalysisWindow window) {
        User caller = requireUser(email);
        FamilyMember me = requireMembership(caller.getId());
        List<FamilyMember> members = memberRepository.findAllByGroupIdOrderByJoinedAtAsc(me.getGroup().getId());

        List<FamilyAnalyticsResponse.MemberAnalytics> blocks = new ArrayList<>();
        Totals combined = new Totals();
        Map<UUID, String> combinedNames = new HashMap<>();

        for (FamilyMember member : members) {
            boolean isMe = member.getId().equals(me.getId());
            if (!isMe && member.getShareScope() == FamilyMember.ShareScope.NONE) {
                // A casa sabe que a pessoa existe e que escolheu não mostrar —
                // o que é diferente de "gastou zero". Por isso totais nulos.
                blocks.add(new FamilyAnalyticsResponse.MemberAnalytics(
                        member.getId(), member.getUser().getName(), false,
                        member.getShareScope().name(), null, List.of()));
                continue;
            }

            Map<UUID, Category> catalog = catalogOf(member);
            Totals totals = sharedTotals(member, isMe, window, catalog);
            Map<UUID, String> names = namesFor(totals.byCategory.keySet(), catalog);

            blocks.add(new FamilyAnalyticsResponse.MemberAnalytics(
                    member.getId(), member.getUser().getName(), isMe,
                    member.getShareScope().name(),
                    new FamilyAnalyticsResponse.Totals(
                            totals.income, totals.expense, totals.income.subtract(totals.expense)),
                    slices(totals, names)));

            combined.add(totals);
            combinedNames.putAll(names);
        }

        return new FamilyAnalyticsResponse(
                new FamilyAnalyticsResponse.Window(window.start(), window.end(), window.monthLabel()),
                blocks,
                new FamilyAnalyticsResponse.Combined(
                        combined.income, combined.expense, combined.income.subtract(combined.expense),
                        slices(combined, combinedNames)));
    }

    /**
     * As linhas da casa: só de quem abriu as linhas (TRANSACTIONS) — e as
     * minhas, sempre. {@code memberId} restringe a um membro (o de outra casa
     * não existe para mim: 404); {@code categoryId} recorta em memória, como na
     * listagem pessoal — a categoria oculta já não veio do banco, então pedir
     * por ela devolve vazio.
     */
    @Transactional(readOnly = true)
    public List<FamilyTransactionResponse> transactions(String email, AnalysisWindow window,
                                                        UUID memberId, UUID categoryId) {
        User caller = requireUser(email);
        FamilyMember me = requireMembership(caller.getId());

        List<FamilyMember> members;
        if (memberId != null) {
            FamilyMember only = memberRepository.findByIdAndGroupId(memberId, me.getGroup().getId())
                    .orElseThrow(() -> new ResourceNotFoundException(FamilyService.MEMBER_NOT_FOUND_MESSAGE));
            members = List.of(only);
        } else {
            members = memberRepository.findAllByGroupIdOrderByJoinedAtAsc(me.getGroup().getId());
        }

        List<FamilyTransactionResponse> rows = new ArrayList<>();
        for (FamilyMember member : members) {
            boolean isMe = member.getId().equals(me.getId());
            if (!isMe && member.getShareScope() != FamilyMember.ShareScope.TRANSACTIONS) continue;

            Set<UUID> hidden = isMe ? Set.of() : effectiveHidden(member, catalogOf(member));
            Set<UUID> shared = isMe ? Set.of() : member.getSharedAccountIds();
            boolean unassigned = isMe || member.isIncludeUnassigned();

            List<BankTransaction> lines = bankTransactionRepository.findSharedInWindow(
                    member.getUser().getId(), window.startInstant(), window.endExclusiveInstant(),
                    hidden, shared, unassigned);
            String memberName = member.getUser().getName();
            for (BankTransaction tx : lines) {
                if (categoryId != null && !categoryId.equals(tx.getCategoryId())) continue;
                rows.add(FamilyTransactionResponse.from(tx, member.getId(), memberName));
            }
        }
        // a lista da casa intercala pessoas: ordem por data, como no extrato
        rows.sort(Comparator.comparing(FamilyTransactionResponse::date).reversed());
        return rows;
    }

    // ------------------------------------------------------------------ apoio

    /**
     * A soma do que ESTE membro mostra na janela, por categoria-RAIZ. Para mim,
     * sem filtro nenhum (coleções vazias, "extrato importado" incluído) — a
     * mesma consulta, para a perna interna sair igual e não haver dois caminhos
     * de código.
     */
    private Totals sharedTotals(FamilyMember member, boolean isMe, AnalysisWindow window,
                                Map<UUID, Category> catalog) {
        Set<UUID> hidden = isMe ? Set.of() : effectiveHidden(member, catalog);
        Set<UUID> shared = isMe ? Set.of() : member.getSharedAccountIds();
        boolean unassigned = isMe || member.isIncludeUnassigned();

        List<BankTransactionRepository.CategoryTotal> rows = bankTransactionRepository.sumByCategoryShared(
                member.getUser().getId(), window.startInstant(), window.endExclusiveInstant(),
                hidden, shared, unassigned);

        Totals totals = new Totals();
        for (BankTransactionRepository.CategoryTotal row : rows) {
            BigDecimal abs = row.getTotal() != null ? row.getTotal().abs() : BigDecimal.ZERO;
            // Mesma regra de sinal da análise pessoal (AnalyticsService.totalsFor):
            // CREDIT é receita, DEBIT é despesa, e TRNTYPE fora do padrão OFX
            // decide pelo sinal. Divergir daqui faria a casa somar diferente da
            // tela "Eu" para as mesmas linhas.
            boolean income = "CREDIT".equalsIgnoreCase(row.getType())
                    || (!"DEBIT".equalsIgnoreCase(row.getType())
                    && row.getTotal() != null && row.getTotal().signum() > 0);

            // Agrupa pela RAIZ, como a análise pessoal (AnalyticsService.rollUp):
            // a linha em "Saúde › Farmácia" soma em Saúde. A raiz é resolvida
            // pelo catálogo do DONO da linha — o mesmo que expande a raiz oculta
            // nas filhas —, porque a subcategoria pessoal só existe nele. Sem
            // isto, "Eu" mostraria "Saúde 300" e "Casa" "Farmácia 100 + Saúde
            // 200" para as mesmas linhas, e a tela, que reaproveita os
            // componentes da Análise, leria como erro.
            UUID rootId = rootIdOf(row.getCategoryId(), catalog);
            CategoryAgg agg = totals.byCategory.computeIfAbsent(rootId, id -> new CategoryAgg());
            agg.count += row.getTxCount();
            if (income) {
                agg.income = agg.income.add(abs);
                totals.income = totals.income.add(abs);
            } else {
                agg.expense = agg.expense.add(abs);
                totals.expense = totals.expense.add(abs);
            }
        }
        return totals;
    }

    /**
     * A raiz da categoria no catálogo do dono da linha: a própria quando já é
     * raiz — ou quando não está no catálogo, caso em que não há o que resolver.
     * Nulo continua nulo: "Sem categoria" não tem raiz.
     */
    private static UUID rootIdOf(UUID categoryId, Map<UUID, Category> catalog) {
        if (categoryId == null) return null;
        Category category = catalog.get(categoryId);
        return category != null ? category.rootCategory().getId() : categoryId;
    }

    /**
     * As categorias ocultas COM as filhas delas. Ocultar "Saúde" tem que ocultar
     * "Saúde › Farmácia": a linha carrega o id da filha, e sem esta expansão o
     * gasto escondido voltaria pela subcategoria. A expansão é por consulta, e
     * não gravada, para valer também para filhas criadas depois.
     */
    static Set<UUID> effectiveHidden(FamilyMember member, Map<UUID, Category> catalog) {
        Set<UUID> hidden = new HashSet<>(member.getHiddenCategoryIds());
        if (hidden.isEmpty()) return hidden;
        for (Category category : catalog.values()) {
            Category parent = category.getParent();
            if (parent != null && member.getHiddenCategoryIds().contains(parent.getId())) {
                hidden.add(category.getId());
            }
        }
        return hidden;
    }

    /**
     * O catálogo do DONO da linha: categoria do sistema tem nome global;
     * pessoal, o nome que o membro deu — e só o catálogo dele o conhece.
     */
    private Map<UUID, Category> catalogOf(FamilyMember member) {
        return categoryRepository.findVisibleTo(member.getUser().getId()).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity(), (a, b) -> a));
    }

    private static Map<UUID, String> namesFor(Set<UUID> categoryIds, Map<UUID, Category> catalog) {
        Map<UUID, String> names = new HashMap<>();
        for (UUID id : categoryIds) {
            if (id == null) continue;
            Category category = catalog.get(id);
            names.put(id, category != null ? category.getName() : UNCATEGORIZED_NAME);
        }
        return names;
    }

    private static List<FamilyAnalyticsResponse.CategorySlice> slices(Totals totals, Map<UUID, String> names) {
        List<FamilyAnalyticsResponse.CategorySlice> slices = new ArrayList<>();
        for (Map.Entry<UUID, CategoryAgg> entry : totals.byCategory.entrySet()) {
            UUID id = entry.getKey();
            CategoryAgg agg = entry.getValue();
            slices.add(new FamilyAnalyticsResponse.CategorySlice(
                    id,
                    id != null ? names.getOrDefault(id, UNCATEGORIZED_NAME) : UNCATEGORIZED_NAME,
                    agg.income, agg.expense, agg.count));
        }
        slices.sort(Comparator.comparing(FamilyAnalyticsResponse.CategorySlice::expense).reversed()
                .thenComparing(FamilyAnalyticsResponse.CategorySlice::income, Comparator.reverseOrder()));
        return slices;
    }

    private FamilyMember requireMembership(UUID userId) {
        return memberRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(FamilyService.NO_FAMILY_MESSAGE));
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
    }

    private static class Totals {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        final Map<UUID, CategoryAgg> byCategory = new LinkedHashMap<>();

        void add(Totals other) {
            income = income.add(other.income);
            expense = expense.add(other.expense);
            other.byCategory.forEach((id, agg) ->
                    byCategory.computeIfAbsent(id, k -> new CategoryAgg()).add(agg));
        }
    }

    private static class CategoryAgg {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        long count;

        void add(CategoryAgg other) {
            income = income.add(other.income);
            expense = expense.add(other.expense);
            count += other.count;
        }
    }
}
