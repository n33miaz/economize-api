package br.com.economize.service.ai;

import lombok.Getter;

/**
 * O provedor de IA do usuário recusou ou falhou. Existe para que a falha NUNCA
 * saia como {@code WebClientResponseException}: o handler global registra o
 * corpo bruto da resposta em log ao tratar aquela exceção, e corpo bruto de
 * provedor é exatamente o lugar onde uma chave ecoada apareceria. Aqui só
 * trafegam o motivo classificado e um texto escrito por nós.
 */
@Getter
public class AiProviderException extends RuntimeException {

    /**
     * Motivo em vocabulário do produto — é o que o app usa para decidir o que
     * dizer ao usuário, sem depender do texto da mensagem.
     *
     * <p>AUTH: chave recusada. MODEL: modelo inexistente ou sem acesso.
     * RATE_LIMIT: cota estourada. NETWORK: não deu para falar com o provedor.
     * PROVIDER: qualquer outra falha do lado de lá.
     */
    public enum Reason {AUTH, MODEL, RATE_LIMIT, NETWORK, PROVIDER}

    private final transient Reason reason;

    public AiProviderException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
