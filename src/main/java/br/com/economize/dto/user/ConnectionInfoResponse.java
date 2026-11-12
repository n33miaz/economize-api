package br.com.economize.dto.user;

import br.com.economize.config.ClientIpResolver.HopKind;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Por onde ESTA requisição chegou, do jeito que a API enxergou: a cadeia de
 * proxies e o IP que o {@code ClientIpResolver} escolheu como sendo o cliente.
 *
 * <p>Serve para conferir o IP enxergado depois de qualquer mudança de
 * hospedagem. O limite de tentativas de login é contado por IP; se a
 * plataforma muda a cadeia (um proxy a mais, uma borda nova na frente), o
 * resolvedor pode passar a escolher o salto errado sem que teste nenhum
 * acuse. Aqui se vê a cadeia real em vez de adivinhá-la.
 *
 * <p>Só cabeçalhos de encaminhamento entram, e só os desta requisição. Nenhum
 * outro cabeçalho é devolvido, de propósito.
 *
 * @param forwardedFor     cada salto do {@code X-Forwarded-For}, na ordem em
 *                         que chegou (esquerda para a direita), com a
 *                         classificação que o resolvedor deu a ele
 * @param cfConnectingIp   {@code CF-Connecting-IP}, se veio
 * @param trueClientIp     {@code True-Client-IP}, se veio
 * @param realIp           {@code X-Real-IP}, se veio
 * @param forwarded        linhas do cabeçalho {@code Forwarded} (RFC 7239)
 * @param remoteAddress    o {@code getRemoteAddress()} da requisição. NÃO é
 *                         necessariamente o soquete: com cabeçalhos
 *                         encaminhados em modo nativo (o caso do Render) ele
 *                         chega reescrito para a ponta esquerda do
 *                         {@code X-Forwarded-For}, que é texto do cliente
 * @param remoteAddressKind classificação do {@code remoteAddress}
 * @param remoteAddressRewritten verdadeiro quando o {@code remoteAddress} é
 *                         igual ao primeiro salto do {@code X-Forwarded-For}:
 *                         sinal de que foi reescrito a partir do cabeçalho
 * @param resolvedClientIp o que o {@code ClientIpResolver} escolheu
 */
public record ConnectionInfoResponse(
        List<Hop> forwardedFor,
        String cfConnectingIp,
        String trueClientIp,
        String realIp,
        List<String> forwarded,
        @Schema(description = "Endereço remoto visto pela aplicação. Pode NÃO ser o soquete: com cabeçalhos "
                + "encaminhados em modo nativo ele vem reescrito para o primeiro valor do X-Forwarded-For.")
        String remoteAddress,
        HopKind remoteAddressKind,
        @Schema(description = "Verdadeiro quando o remoteAddress é igual ao primeiro salto do X-Forwarded-For, "
                + "sinal de que foi reescrito a partir do cabeçalho.")
        boolean remoteAddressRewritten,
        String resolvedClientIp) {

    /**
     * Um salto da cadeia e o que ele é para o resolvedor: {@code INTERNAL}
     * (rede interna), {@code CLOUDFLARE} (faixa de proxy confiável),
     * {@code PUBLIC} (onde a leitura para) ou {@code UNREADABLE}.
     */
    public record Hop(String address, HopKind kind) {
    }
}
