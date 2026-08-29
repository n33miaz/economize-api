package br.com.economize.dto.recurrence;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Atualização parcial: só os campos presentes são aplicados. amountType e
 * cadence chegam como texto e são validados no service — enum no DTO derrubaria
 * o decode do JSON inteiro com 500 em vez de responder 400 com mensagem útil.
 *
 * <p>Ritmo (cadence/anchorDay) e vigência (startsAt/endsAt) são editáveis
 * porque o 409 de colisão promete "edite-a ou reative-a": sem eles, quem errou a
 * data de início ou o dia da cobrança não tinha saída — a série agendada com
 * vínculos não é apagada pelo DELETE (vira descarte) e recriar esbarra no mesmo
 * 409.
 */
public record UpdateRecurringSeriesRequest(
        @Size(max = 160, message = "Nome de exibição deve ter no máximo 160 caracteres")
        String displayName,

        UUID categoryId,

        Boolean active,

        String amountType,

        @DecimalMin(value = "0.00", inclusive = false, message = "Valor esperado deve ser positivo")
        BigDecimal expectedAmount,

        String cadence,

        @Min(value = 1, message = "Dia âncora deve estar entre 1 e 31")
        @Max(value = 31, message = "Dia âncora deve estar entre 1 e 31")
        Integer anchorDay,

        LocalDate startsAt,

        LocalDate endsAt
) {
}
