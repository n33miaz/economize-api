package br.com.economize.dto.recurrence;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Agendamento manual de série recorrente (gasto fixo/renda do usuário). Enums
 * chegam como texto e são validados no service — enum no DTO derrubaria o
 * decode do JSON inteiro com 500 em vez de responder 400 com mensagem útil.
 */
public record CreateRecurringSeriesRequest(
        @NotBlank(message = "Nome de exibição é obrigatório")
        @Size(max = 160, message = "Nome de exibição deve ter no máximo 160 caracteres")
        String displayName,

        @NotBlank(message = "Fluxo é obrigatório (EXPENSE ou INCOME)")
        String flow,

        @NotBlank(message = "Cadência é obrigatória (MONTHLY, WEEKLY ou QUARTERLY)")
        String cadence,

        @Min(value = 1, message = "Dia âncora deve estar entre 1 e 31")
        @Max(value = 31, message = "Dia âncora deve estar entre 1 e 31")
        Integer anchorDay,

        @NotNull(message = "Valor esperado é obrigatório")
        @DecimalMin(value = "0.00", inclusive = false, message = "Valor esperado deve ser positivo")
        BigDecimal expectedAmount,

        String amountType,

        UUID categoryId,

        // texto livre que vira merchant_key (mesmo extrator da detecção) para a
        // varredura conciliar transações reais; sem hint, deriva do displayName
        @Size(max = 160, message = "matchHint deve ter no máximo 160 caracteres")
        String matchHint,

        LocalDate startsAt,

        LocalDate endsAt
) {
}
