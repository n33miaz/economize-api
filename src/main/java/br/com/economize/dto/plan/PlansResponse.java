package br.com.economize.dto.plan;

import br.com.economize.model.Plan;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A oferta de planos para o usuário autenticado. {@code current} é o plano
 * VIGENTE (um PLUS vencido aparece como FREE); {@code checkoutAvailable} falso
 * diz ao app para oferecer "tenho interesse" em vez de "assinar";
 * {@code interestRegistered} evita perguntar de novo a quem já respondeu.
 */
public record PlansResponse(
        Plan current,
        List<PlanOption> plans,
        boolean checkoutAvailable,
        boolean interestRegistered,
        /**
         * Até quando o plano pago vale. Nulo em FREE e no PLUS sem prazo.
         *
         * <p>É o número que a tela precisa para dizer a data exata, em vez de
         * "sua assinatura continua ativa" — que é a frase que não responde
         * nada a quem acabou de cancelar.
         */
        OffsetDateTime activeUntil,
        /** Quando a pessoa pediu para sair; nulo = não pediu (EC-208). */
        OffsetDateTime cancelledAt
) {

    public record PlanOption(Plan id, String name, BigDecimal priceMonthly, List<String> features) {
    }
}
