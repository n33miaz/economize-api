package br.com.economize.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * Configuração de IA em vigor para a conta autenticada.
 *
 * <p><b>A chave NÃO está aqui e não estará.</b> Nem cifrada, nem mascarada de um
 * jeito que permita reconstruí-la. O único resquício é {@code keyLast4}, quatro
 * caracteres que servem só para o usuário reconhecer qual das chaves dele está
 * cadastrada — a mesma pista que os painéis de Stripe, GitHub e AWS mostram.
 */
@Schema(description = "Configuração de IA em vigor. Nunca inclui a chave do usuário.")
public record AiSettingsResponse(

        @Schema(description = "USER quando a conta tem chave própria; SERVER quando usa a chave do servidor",
                allowableValues = {"USER", "SERVER"}, example = "SERVER")
        String source,

        @Schema(description = "Provedor em vigor", example = "GEMINI")
        String provider,

        @Schema(description = "Modelo em vigor", example = "gemini-2.0-flash")
        String model,

        @Schema(description = "Últimos 4 caracteres da chave do usuário; nulo quando a origem é SERVER",
                example = "9f2a")
        String keyLast4,

        @Schema(description = "OK: chave própria legível. UNREADABLE: cadastrada mas ilegível com a "
                + "chave-mestra atual — precisa recadastrar. SERVER_KEY: a conta não tem chave própria.",
                allowableValues = {"OK", "UNREADABLE", "SERVER_KEY"}, example = "SERVER_KEY")
        String keyStatus,

        @Schema(description = "Esta instalação aceita chave própria? Falso quando o servidor está sem "
                + "chave-mestra de criptografia — o app deve esconder a opção.", example = "true")
        boolean byokAvailable,

        @Schema(description = "Quando a configuração própria foi salva pela última vez; nulo quando não há")
        OffsetDateTime updatedAt
) {

    public static final String SOURCE_USER = "USER";
    public static final String SOURCE_SERVER = "SERVER";
    public static final String KEY_OK = "OK";
    public static final String KEY_UNREADABLE = "UNREADABLE";
    public static final String KEY_SERVER = "SERVER_KEY";
}
