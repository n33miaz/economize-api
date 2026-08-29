package br.com.economize.service.ai;

/**
 * Tudo o que uma chamada de IA precisa saber, já resolvido: para onde ir, com
 * qual modelo e com qual chave.
 *
 * <p><b>O {@code toString} é sobrescrito e isso não é estilo, é requisito.</b>
 * Record gera um toString com TODOS os componentes — e este carrega uma chave em
 * claro. Bastaria um {@code log.debug("target={}", target)} distraído, ou a
 * inclusão do objeto numa mensagem de exceção, para o segredo do usuário ir
 * parar no log estruturado. Aqui ele nunca vai.
 */
public record AiCallTarget(AiProvider provider, String model, String endpoint,
                           String apiKey, Integer maxTokens) {

    @Override
    public String toString() {
        return "AiCallTarget[provider=" + provider + ", model=" + model + ", chave=oculta]";
    }
}
