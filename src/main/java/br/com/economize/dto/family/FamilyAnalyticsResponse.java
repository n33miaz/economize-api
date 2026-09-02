package br.com.economize.dto.family;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A análise da casa numa janela (EC-149): um bloco por membro e o combinado.
 *
 * <p>Quem escolheu {@code NONE} aparece na lista com {@code totals} nulo e
 * categorias vazias — a casa sabe que a pessoa existe e que escolheu não
 * mostrar, o que é diferente de "gastou zero". Por isso os totais não são
 * primitivos: nulo aqui é informação, não ausência.
 *
 * <p>Os totais de cada membro saem das MESMAS linhas filtradas que as
 * categorias, nunca do total pessoal dele: se saíssem, o outro deduziria o
 * valor de uma categoria oculta pela diferença.
 */
public record FamilyAnalyticsResponse(
        Window window,
        List<MemberAnalytics> members,
        Combined combined
) {

    /** {@code month} nulo quando a janela é ancorada (start/end). */
    public record Window(LocalDate start, LocalDate end, String month) {
    }

    public record MemberAnalytics(
            UUID memberId,
            String name,
            boolean isMe,
            /* o que ESTE membro compartilha — para a tela dizer "não mostra" */
            String shareScope,
            Totals totals,
            List<CategorySlice> categories
    ) {
    }

    public record Totals(BigDecimal income, BigDecimal expense, BigDecimal net) {
    }

    /**
     * {@code categoryName} viaja na resposta porque o catálogo de categorias
     * pessoais é de cada membro: a tela de quem olha não teria como resolver o
     * nome que o outro deu à categoria dele.
     */
    public record CategorySlice(
            UUID categoryId,
            String categoryName,
            BigDecimal income,
            BigDecimal expense,
            long txCount
    ) {
    }

    /** Soma dos membros que mostram algo (escopo diferente de NONE). */
    public record Combined(
            BigDecimal income,
            BigDecimal expense,
            BigDecimal net,
            List<CategorySlice> categories
    ) {
    }
}
