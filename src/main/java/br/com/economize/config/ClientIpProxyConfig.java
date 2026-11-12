package br.com.economize.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Leva a propriedade {@code economize.client-ip.trusted-proxies} até o
 * {@link ClientIpResolver}, uma vez, na subida.
 *
 * <p>O resolvedor é estático de propósito (um só, para o limite de requisições
 * e para o login), e a lista embutida nele são as faixas da Cloudflare. A
 * propriedade existe para trocar a lista sem deploy quando a Cloudflare
 * publicar faixa nova ou a hospedagem mudar. Em branco, vale a embutida; com
 * entrada inválida, a aplicação não sobe — melhor do que subir contando o
 * limite de login pelo endereço errado.
 */
@Configuration
public class ClientIpProxyConfig {

    public ClientIpProxyConfig(
            @Value("${economize.client-ip.trusted-proxies:}") List<String> trustedProxies) {
        ClientIpResolver.configureTrustedProxies(trustedProxies);
    }
}
