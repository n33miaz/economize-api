package br.com.economize.security;

import br.com.economize.service.integration.IntegrationKeyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

/**
 * A cadeia de segurança das rotas de integração (V44), separada da cadeia do
 * app.
 *
 * <p><b>Por que uma cadeia própria, e não mais uma regra na cadeia do app.</b>
 * O menor privilégio aqui é estrutural: quando a rota casa
 * {@code /api/v1/integration/**}, esta cadeia responde e a do app NEM RODA —
 * o filtro de JWT não está nela, então token de sessão não autentica nada
 * aqui. E o filtro da chave só existe nesta cadeia, então a chave não
 * autentica nada lá. Nenhuma das duas garantias depende de alguém lembrar de
 * um {@code if} de caminho dentro de um filtro.
 *
 * <p>A ordem é o que faz isso valer: esta cadeia tem precedência máxima e a do
 * app, sem ordem declarada, fica por último como a de "todo o resto".
 *
 * <p><b>O casamento é por segmento.</b> {@code /api/v1/integration/**} não
 * casa {@code /api/v1/integration-keys}: a gestão das chaves continua na
 * cadeia do app, exigindo o JWT do dono — uma chave não cria nem revoga chave.
 */
@Configuration
public class IntegrationSecurityConfig {

    static final String INTEGRATION_PATHS = "/api/v1/integration/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityWebFilterChain integrationSecurityWebFilterChain(ServerHttpSecurity http,
                                                                    IntegrationKeyService keyService) {
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers(INTEGRATION_PATHS))
                // CSRF fica LIGADO, de propósito: a cadeia só aceita GET, que o
                // filtro de CSRF não toca; qualquer verbo de escrita morre nele
                // antes mesmo da regra de autorização
                .authorizeExchange(exchanges -> exchanges
                        // A autoridade, e não só "autenticado": se um dia outro
                        // filtro autenticar nesta cadeia por engano, ele não
                        // traz INTEGRATION e continua de fora
                        .pathMatchers(HttpMethod.GET, INTEGRATION_PATHS)
                        .hasAuthority(IntegrationKeyAuthenticationFilter.AUTHORITY)
                        // A integração é só leitura: qualquer outro verbo é
                        // recusado mesmo com chave válida
                        .anyExchange().denyAll())
                .addFilterAt(new IntegrationKeyAuthenticationFilter(keyService),
                        SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
