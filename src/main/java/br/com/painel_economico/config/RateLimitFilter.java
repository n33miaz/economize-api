package br.com.painel_economico.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter implements WebFilter {

    private static final Set<String> EXPENSIVE_PREFIXES = Set.of("/api/v1/chat", "/api/v1/reports");

    private final ConcurrentMap<String, Bucket> standardBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bucket> expensiveBuckets = new ConcurrentHashMap<>();

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String key = clientKey(exchange);
        boolean expensive = EXPENSIVE_PREFIXES.stream().anyMatch(path::startsWith);

        Bucket bucket = expensive
                ? expensiveBuckets.computeIfAbsent(key, k -> createBucket(10, Duration.ofMinutes(1)))
                : standardBuckets.computeIfAbsent(key, k -> createBucket(60, Duration.ofMinutes(1)));

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            exchange.getResponse().getHeaders().set("X-RateLimit-Remaining",
                    String.valueOf(probe.getRemainingTokens()));
            return chain.filter(exchange);
        }

        long retrySeconds = probe.getNanosToWaitForRefill() / 1_000_000_000L;
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(Math.max(retrySeconds, 1)));
        return exchange.getResponse().setComplete();
    }

    private Bucket createBucket(long capacity, Duration window) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(capacity).refillGreedy(capacity, window).build())
                .build();
    }

    private String clientKey(ServerWebExchange exchange) {
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return "u:" + auth.substring(7);
        var remoteAddress = exchange.getRequest().getRemoteAddress();
        return remoteAddress != null ? "ip:" + remoteAddress.getAddress().getHostAddress() : "anon";
    }
}
