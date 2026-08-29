package br.com.economize.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * O que o app precisa para montar a tela de escolha sem pedir nada ao provedor:
 * quais provedores existem, quais modelos são aceitos em cada um e onde o
 * usuário emite a chave dele.
 */
@Schema(description = "Catálogo de provedores e modelos aceitos")
public record AiProviderCatalogResponse(

        @Schema(description = "Falso quando o servidor está sem chave-mestra: cadastrar chave própria "
                + "responderia 503 e o app deve esconder a opção")
        boolean byokAvailable,

        List<ProviderOption> providers
) {

    @Schema(description = "Um provedor e seus modelos")
    public record ProviderOption(

            @Schema(description = "Valor a mandar em provider no cadastro", example = "ANTHROPIC")
            String id,

            @Schema(description = "Nome para exibir", example = "Anthropic Claude")
            String label,

            @Schema(description = "Modelo usado quando o cadastro vem sem model", example = "claude-haiku-4-5")
            String defaultModel,

            @Schema(description = "Modelos aceitos; qualquer outro valor responde 400")
            List<String> models,

            @Schema(description = "Página onde o usuário emite a própria chave",
                    example = "https://console.anthropic.com/settings/keys")
            String apiKeyUrl
    ) {
    }
}
