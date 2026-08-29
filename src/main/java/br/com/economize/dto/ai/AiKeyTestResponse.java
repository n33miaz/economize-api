package br.com.economize.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Resultado do teste de chave.
 *
 * <p><b>Vem em 200 mesmo quando a chave é recusada</b>, e isso é intencional: o
 * teste RODOU, e a resposta é "não". Erro de HTTP diria que a rota falhou, o que
 * é diferente. Além disso, mantém a explicação num corpo desenhado por nós em
 * vez de um ProblemDetail que alguém poderia acabar preenchendo com o texto do
 * provedor — que é justamente onde uma chave ecoada apareceria.
 */
@Schema(description = "Resultado do teste; ok=false não é erro de HTTP")
public record AiKeyTestResponse(

        @Schema(description = "O provedor aceitou a chave e respondeu?", example = "true")
        boolean ok,

        @Schema(description = "Provedor testado", example = "OPENAI")
        String provider,

        @Schema(description = "Modelo testado", example = "gpt-4o-mini")
        String model,

        @Schema(description = "Nulo quando ok. AUTH: chave recusada. MODEL: modelo inexistente ou sem "
                + "acesso. RATE_LIMIT: cota estourada. NETWORK: não deu para falar com o provedor. "
                + "PROVIDER: outra falha do lado de lá.",
                allowableValues = {"AUTH", "MODEL", "RATE_LIMIT", "NETWORK", "PROVIDER"})
        String reason,

        @Schema(description = "Texto pronto para exibir. Escrito por nós — nunca o corpo do provedor.")
        String message,

        @Schema(description = "Tempo da ida e volta ao provedor, em milissegundos", example = "812")
        long latencyMs
) {
}
