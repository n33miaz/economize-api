package br.com.economize.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Cadastro (ou troca) da chave própria de IA. Salvar SUBSTITUI o que houvesse —
 * chave antiga não é guardada em lugar nenhum.
 */
@Schema(description = "Provedor, modelo e chave do próprio usuário")
public record SaveAiSettingsRequest(

        @Schema(description = "GEMINI, OPENAI, ANTHROPIC ou OPENROUTER", example = "OPENAI")
        @NotBlank(message = "informe o provedor")
        String provider,

        @Schema(description = "Modelo do provedor. Em branco usa o primeiro da lista daquele provedor.",
                example = "gpt-4o-mini")
        String model,

        @Schema(description = "A chave do usuário no provedor. Enviada uma única vez: nunca volta em "
                + "nenhuma resposta.", example = "sk-...")
        @NotBlank(message = "informe a chave do provedor")
        String apiKey
) {
}
