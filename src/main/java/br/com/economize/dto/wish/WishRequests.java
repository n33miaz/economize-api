package br.com.economize.dto.wish;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Os corpos de entrada do domínio de desejos e renda.
 *
 * <p>Ficam juntos porque são pequenos e se leem melhor lado a lado do que
 * espalhados em oito arquivos de dez linhas. Enums chegam como texto e são
 * validados no service — enum no record derrubaria a desserialização inteira
 * com 500 em vez de responder 400 dizendo qual valor era esperado.
 */
public final class WishRequests {

    private WishRequests() {
    }

    public record CreateWish(
            @NotBlank(message = "Nome do desejo é obrigatório")
            @Size(max = 120, message = "Nome deve ter no máximo 120 caracteres")
            String name,

            @NotNull(message = "Valor do desejo é obrigatório")
            @DecimalMin(value = "0.00", inclusive = false, message = "Valor deve ser positivo")
            @Digits(integer = 15, fraction = 4, message = "Valor fora da faixa aceita")
            BigDecimal targetAmount,

            @DecimalMin(value = "0.00", message = "Valor já guardado não pode ser negativo")
            @Digits(integer = 15, fraction = 4, message = "Valor fora da faixa aceita")
            BigDecimal savedAmount,

            UUID categoryId,

            LocalDate targetDate,

            @Size(max = 400, message = "Observação deve ter no máximo 400 caracteres")
            String note
    ) {
    }

    /**
     * Todos os campos opcionais: o PATCH altera só o que veio. É por isso que
     * {@code status} é texto livre validado no service — distinguir "não veio"
     * de "veio inválido" é o que permite responder 400 com a lista de valores.
     */
    public record UpdateWish(
            @Size(max = 120, message = "Nome deve ter no máximo 120 caracteres")
            String name,

            @DecimalMin(value = "0.00", inclusive = false, message = "Valor deve ser positivo")
            @Digits(integer = 15, fraction = 4, message = "Valor fora da faixa aceita")
            BigDecimal targetAmount,

            @DecimalMin(value = "0.00", message = "Valor já guardado não pode ser negativo")
            @Digits(integer = 15, fraction = 4, message = "Valor fora da faixa aceita")
            BigDecimal savedAmount,

            UUID categoryId,

            LocalDate targetDate,

            @Size(max = 400, message = "Observação deve ter no máximo 400 caracteres")
            String note,

            String status
    ) {
    }

    /** Confirmação da compra: fecha o ciclo desejo → meta → compra. */
    public record PurchaseWish(
            LocalDate purchasedAt,

            /* transação do extrato que comprova a compra, quando o usuário souber */
            UUID transactionId
    ) {
    }

    public record CreateIncomeSource(
            @NotBlank(message = "Tipo da fonte é obrigatório")
            String kind,

            @NotBlank(message = "Nome da fonte é obrigatório")
            @Size(max = 120, message = "Nome deve ter no máximo 120 caracteres")
            String name,

            @DecimalMin(value = "0.00", inclusive = false, message = "Valor deve ser positivo")
            @Digits(integer = 15, fraction = 4, message = "Valor fora da faixa aceita")
            BigDecimal expectedAmount,

            @Min(value = 1, message = "Dia deve estar entre 1 e 31")
            @Max(value = 31, message = "Dia deve estar entre 1 e 31")
            Integer anchorDay,

            /* cadastro manual já nasce confirmado; a sugestão do extrato, não */
            Boolean confirmed
    ) {
    }

    public record UpdateIncomeSource(
            @Size(max = 120, message = "Nome deve ter no máximo 120 caracteres")
            String name,

            @DecimalMin(value = "0.00", inclusive = false, message = "Valor deve ser positivo")
            @Digits(integer = 15, fraction = 4, message = "Valor fora da faixa aceita")
            BigDecimal expectedAmount,

            @Min(value = 1, message = "Dia deve estar entre 1 e 31")
            @Max(value = 31, message = "Dia deve estar entre 1 e 31")
            Integer anchorDay,

            Boolean confirmed,

            Boolean active
    ) {
    }

    /** A jornada. PUT porque é 1:1 com o usuário — cria ou substitui. */
    public record SaveWorkProfile(
            @NotNull(message = "Dias por semana é obrigatório")
            @Min(value = 1, message = "Dias por semana deve estar entre 1 e 7")
            @Max(value = 7, message = "Dias por semana deve estar entre 1 e 7")
            Integer daysPerWeek,

            @NotNull(message = "Horas por dia é obrigatório")
            @DecimalMin(value = "0.00", inclusive = false, message = "Horas por dia deve ser positivo")
            // o teto do dia é o dia; o banco tem o mesmo CHECK, e a validação
            // aqui existe para responder 400 em vez de estourar no INSERT
            @DecimalMax(value = "24.00", message = "Horas por dia deve ser no máximo 24")
            @Digits(integer = 2, fraction = 2, message = "Horas por dia deve ter no máximo duas casas")
            BigDecimal hoursPerDay
    ) {
    }
}
