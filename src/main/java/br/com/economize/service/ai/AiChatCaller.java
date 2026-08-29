package br.com.economize.service.ai;

/**
 * Uma chamada de IA já resolvida: quem pergunta não precisa saber se por trás
 * está a chave do servidor (o caminho de sempre) ou a chave do próprio usuário
 * (EC-107).
 */
public interface AiChatCaller {

    /** Uma pergunta, uma resposta em texto. */
    String complete(String systemPrompt, String userPrompt);

    /** A chamada sai na chave do usuário? Falso = chave do servidor. */
    boolean userOwned();

    /** Descrição sem segredo, para log e diagnóstico. */
    String describe();
}
