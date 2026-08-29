package br.com.economize.service.ai;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Provedores de IA que o usuário pode escolher no EC-107.
 *
 * <p>Os quatro falam o mesmo protocolo de fio — o {@code /chat/completions} da
 * OpenAI. Google e OpenRouter publicam endpoints compatíveis, e a Anthropic
 * mantém uma camada de compatibilidade no mesmo caminho. É por isso que a API
 * consegue atender os quatro com UM cliente HTTP e ZERO dependência nova; o que
 * muda entre eles é a URL, o nome do modelo e pouco mais. O preço dessa escolha
 * está anotado em {@link AiProviderProperties}.
 */
public enum AiProvider {

    GEMINI,
    OPENAI,
    ANTHROPIC,
    OPENROUTER;

    /** Chave usada nas properties (minúscula) e no catálogo devolvido ao app. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Aceita o valor vindo do cliente sem se importar com caixa nem espaço.
     * Devolve vazio em vez de lançar: quem chama é que sabe se isso é um 400 de
     * requisição ou uma linha estranha lida do banco.
     */
    public static Optional<AiProvider> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(p -> p.name().equals(normalized)).findFirst();
    }
}
