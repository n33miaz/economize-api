package br.com.economize.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Pedido de teste de chave. Corpo OPCIONAL: sem ele, testa a chave já
 * cadastrada; com {@code apiKey}, testa uma chave que ainda não foi salva — é o
 * que permite ao app oferecer "Testar" antes de "Salvar", sem gravar nada.
 */
@Schema(description = "Chave a testar; corpo ausente testa a chave já cadastrada")
public record AiKeyTestRequest(

        @Schema(description = "Obrigatório quando apiKey vem preenchida", example = "OPENAI")
        String provider,

        @Schema(description = "Em branco usa o modelo padrão do provedor", example = "gpt-4o-mini")
        String model,

        @Schema(description = "Chave a testar. Não é gravada por esta rota.", example = "sk-...")
        String apiKey
) {
}
