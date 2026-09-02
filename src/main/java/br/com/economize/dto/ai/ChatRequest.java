package br.com.economize.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A pergunta enviada ao assistente.
 *
 * <p>Morava dentro do arquivo do controller, como classe package-private: não
 * aparecia na documentação e não passava por validação nenhuma.
 */
public record ChatRequest(
        /*
         * O teto do app é 500 caracteres (AiAssistant.tsx). Aqui o limite é
         * mais folgado de propósito — ele existe para conter cliente que não é
         * o app, já que cada pergunta vira consumo pago no provedor de IA.
         */
        @Schema(description = "Pergunta do usuário", example = "Quanto gastei com mercado neste mês?")
        @NotBlank(message = "A mensagem não pode estar vazia")
        @Size(max = 2000, message = "A mensagem não pode passar de 2000 caracteres")
        String message
) {
}
