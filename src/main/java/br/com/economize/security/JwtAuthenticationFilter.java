package br.com.economize.security;

import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.lang.NonNull;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.Optional;

@Component
public class JwtAuthenticationFilter implements WebFilter {

    private final JwtUtil jwtUtil;

    /**
     * Conferência da versão da sessão (V45). Por {@link ObjectProvider} porque
     * os testes de fatia ({@code @WebFluxTest}) montam este filtro sem a camada
     * de dados; neles a conferência fica de fora e vale só assinatura e
     * validade, como antes. Na aplicação inteira o serviço sempre existe — o
     * {@code SessionVersionRevocationAuthTest} sobe o contexto completo e prova
     * que um token revogado leva 401.
     */
    private final ObjectProvider<SessionVersionService> sessionVersions;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, ObjectProvider<SessionVersionService> sessionVersions) {
        this.jwtUtil = jwtUtil;
        this.sessionVersions = sessionVersions;
    }

    @Override
    @NonNull
    @SuppressWarnings("null")
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            if (jwtUtil.isTokenValid(token)) {
                Claims claims = jwtUtil.extractClaims(token);
                String email = claims.getSubject();
                int tokenVersion = JwtUtil.sessionVersionOf(claims);

                return isCurrentSession(email, tokenVersion).flatMap(current -> {
                    // Sessão revogada segue como anônima, igual a token inválido:
                    // a rota protegida responde 401 e a pública nem percebe
                    if (!current) return chain.filter(exchange);
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            email, null, Collections.emptyList());
                    return chain.filter(exchange)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
                });
            }
        }
        return chain.filter(exchange);
    }

    /**
     * O token ainda é da sessão atual da conta? Com o valor em cache a resposta
     * sai na hora, sem trocar de thread; sem ele, a consulta ao banco (JPA,
     * bloqueante) vai para o boundedElastic, longe do event loop.
     */
    private Mono<Boolean> isCurrentSession(String email, int tokenVersion) {
        SessionVersionService service = sessionVersions.getIfAvailable();
        if (service == null) return Mono.just(true);

        Integer cached = service.cachedVersion(email);
        if (cached != null) return Mono.just(cached == tokenVersion);

        return Mono.fromCallable(() -> service.currentVersion(email))
                .subscribeOn(Schedulers.boundedElastic())
                .map((Optional<Integer> current) -> current.map(v -> v == tokenVersion).orElse(false));
    }
}
