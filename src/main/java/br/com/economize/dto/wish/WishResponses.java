package br.com.economize.dto.wish;

import br.com.economize.model.IncomeSource;
import br.com.economize.model.Wish;
import br.com.economize.model.WorkProfile;
import br.com.economize.service.wish.WishBaseline;
import br.com.economize.service.wish.WishProjection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * As respostas do domínio de desejos e renda.
 *
 * <p>Campo nulo aqui quer dizer <b>"ainda não dá para saber"</b>, e o app trata
 * cada nulo como um convite ("me diga sua jornada"), nunca como zero. Foi por
 * isso que a projeção não usa primitivos: {@code int} sem valor viraria 0, e
 * "custa 0 horas de trabalho" é uma mentira convincente.
 */
public final class WishResponses {

    private WishResponses() {
    }

    public record WishItem(
            UUID id,
            String name,
            BigDecimal targetAmount,
            BigDecimal savedAmount,
            UUID categoryId,
            String status,
            LocalDate targetDate,
            String note,
            LocalDate purchasedAt,
            UUID purchaseTransactionId,
            Projection projection
    ) {
        public static WishItem from(Wish wish, WishProjection projection) {
            return new WishItem(
                    wish.getId(),
                    wish.getName(),
                    wish.getTargetAmount(),
                    wish.getSavedAmount(),
                    wish.getCategoryId(),
                    wish.getStatus().name(),
                    wish.getTargetDate(),
                    wish.getNote(),
                    wish.getPurchasedAt(),
                    wish.getPurchaseTransactionId(),
                    Projection.from(projection));
        }
    }

    public record Projection(
            BigDecimal remaining,
            BigDecimal hoursOfWork,
            BigDecimal workDays,
            Integer monthsToAfford,
            LocalDate estimatedDate,
            Integer installments,
            BigDecimal maxInstallment,
            boolean achieved,
            List<WhatIf> whatIfs
    ) {
        static Projection from(WishProjection p) {
            return new Projection(
                    p.remaining(), p.hoursOfWork(), p.workDays(), p.monthsToAfford(),
                    p.estimatedDate(), p.installments(), p.maxInstallment(), p.achieved(),
                    p.whatIfs().stream()
                            .map(w -> new WhatIf(w.percentOfExpense(), w.monthlyCut(),
                                    w.months(), w.estimatedDate(), w.monthsEarlier()))
                            .toList());
        }
    }

    public record WhatIf(
            int percentOfExpense,
            BigDecimal monthlyCut,
            Integer months,
            LocalDate estimatedDate,
            Integer monthsEarlier
    ) {
    }

    /**
     * O retrato financeiro que vale para todos os desejos da lista.
     *
     * <p>{@code gaps} é a parte acionável: em vez de esconder que faltou dado, a
     * API diz exatamente o que falta e a tela vira isso em um botão.
     */
    public record Baseline(
            BigDecimal workIncome,
            BigDecimal hourlyRate,
            BigDecimal hoursPerMonth,
            BigDecimal monthlyLeftover,
            BigDecimal monthlyExpense,
            int cyclesConsidered,
            List<String> gaps
    ) {
        public static Baseline from(WishBaseline b) {
            return new Baseline(b.workIncome(), b.hourlyRate(), b.hoursPerMonth(),
                    b.monthlyLeftover(), b.monthlyExpense(), b.cyclesConsidered(), b.gaps());
        }
    }

    public record WishList(Baseline baseline, List<WishItem> wishes) {
    }

    public record IncomeSourceItem(
            UUID id,
            String kind,
            String name,
            BigDecimal expectedAmount,
            Short anchorDay,
            boolean confirmed,
            boolean active,
            UUID seriesId
    ) {
        public static IncomeSourceItem from(IncomeSource source) {
            return new IncomeSourceItem(
                    source.getId(), source.getKind().name(), source.getName(),
                    source.getExpectedAmount(), source.getAnchorDay(),
                    source.isConfirmed(), source.isActive(), source.getSeriesId());
        }
    }

    public record WorkProfileItem(Integer daysPerWeek, BigDecimal hoursPerDay, BigDecimal hoursPerMonth) {
        public static WorkProfileItem from(WorkProfile profile, BigDecimal hoursPerMonth) {
            if (profile == null) return null;
            return new WorkProfileItem((int) profile.getDaysPerWeek(), profile.getHoursPerDay(), hoursPerMonth);
        }
    }

    /**
     * O que a tela de renda mostra de uma vez: as fontes cadastradas, a jornada
     * e as fontes que o extrato SUGERE mas ninguém confirmou ainda.
     */
    public record IncomeOverview(
            List<IncomeSourceItem> sources,
            WorkProfileItem workProfile,
            List<Suggestion> suggestions
    ) {
    }

    /**
     * Uma fonte que o motor de recorrência encontrou no extrato e que o usuário
     * ainda não confirmou. Não vira fonte sozinha: confirmar quanto se ganha é
     * decisão de quem ganha.
     */
    public record Suggestion(
            UUID seriesId,
            String suggestedKind,
            String name,
            BigDecimal expectedAmount,
            Short anchorDay
    ) {
    }
}
