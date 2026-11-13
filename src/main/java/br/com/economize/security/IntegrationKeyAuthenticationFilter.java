package br.com.economize.security;

import br.com.economize.service.integration.IntegrationKeyService;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Autentica pela chave de integração (V44) — e só existe dentro da cadeia de
 * {@link IntegrationSecurityConfig}, que casa apenas {@code /api/v1/integration/**}.
 *
 * <p><b>Não é {@code @Component} de propósito.</b> Um {@code WebFilter} que é
 * bean vira filtro GLOBAL do WebFlux, rodando em toda rota — exatamente o que
 * a chave não pode fazer. Construído à mão dentro da configuração, ele só
 * existe na cadeia das rotas de integração.
 *
 * <p><b>Este filtro nunca responde 401.</b> Chave ausente, malformada,
 * desconhecida ou revogada — e JWT de sessão apresentado aqui — dão todos no
 * mesmo lugar: a requisição segue sem autenticação e é a cadeia que recusa.
 * Assim o corpo e os cabeçalhos da recusa são idênticos por construção, e quem
 * está tentando não aprende se o segredo que testou já existiu.
 *
 * <p>O principal é o e-mail do dono, como no JWT: os serviços recebem o mesmo
 * {@code @AuthenticationPrincipal String email} e não precisam saber por onde
 * a pessoa entrou.
 */
public class IntegrationKeyAuthenticationFilter implements WebFilter {

    /** A autoridade que só este filtro concede; a cadeia de integração a exige. */
    public static final String AUTHORITY = "INTEGRATION";

    private static final String BEARER = "Bearer ";

    private final IntegrationKeyService keyService;

    public IntegrationKeyAuthenticationFilter(IntegrationKeyService keyService) {
        this.keyService = keyService;
    }

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return chain.filter(exchange);
        }
        String secret = header.substring(BEARER.length());

        // A conferência vai ao banco (JPA, bloqueante): fora do event loop
        return Mono.fromCallable(() -> keyService.authenticate(secret))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(owner -> owner
                        .map(email -> chain.filter(exchange)
                                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                                        new UsernamePasswordAuthenticationToken(email, null,
                                                List.of(new SimpleGrantedAuthority(AUTHORITY))))))
                        .orElseGet(() -> chain.filter(exchange)));
    }
}
