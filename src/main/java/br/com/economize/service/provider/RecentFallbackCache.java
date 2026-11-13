package br.com.economize.service.provider;

import br.com.economize.config.MarketSourcesProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Janela curta para a resposta de FALLBACK (snapshot, lista vazia, "ninguém
 * conhece o papel") dos serviços de mercado.
 *
 * <p>
 * O cache de 30 min a 6 h guarda só dado vivo — de propósito, para uma falha
 * de segundos não ficar servida pelo prazo inteiro. Mas sem nada no lugar, cada
 * abertura de tela durante uma falha refazia todas as chamadas e esperava o
 * timeout (5–10 s): com uma das 7 séries do BCB fora, toda abertura de
 * Investimentos pagava as 7 de novo; com AwesomeAPI e fonte alternativa fora,
 * cada gráfico gastava mais 1 da fatia diária do histórico; e um símbolo
 * estrangeiro desconhecido ia ao Yahoo e ao Stooq a cada tela. Aqui o fallback
 * fica guardado por poucos minutos ({@link MarketSourcesProperties#getFallbackWindow()},
 * 2 min por padrão): longo o bastante para cortar a rajada, curto o bastante
 * para a fonte que voltou aparecer logo.
 *
 * <p>
 * Chaves são texto com o dono na frente ({@code macro}, {@code treasury},
 * {@code historical:USD:7}, {@code quote:US:VT}): um só cache pequeno para os
 * quatro serviços, em vez de quatro.
 */
@Component
public class RecentFallbackCache {

    private final Cache<String, Object> recent;

    @Autowired
    public RecentFallbackCache(MarketSourcesProperties properties) {
        this(properties.getFallbackWindow());
    }

    public RecentFallbackCache(Duration window) {
        this.recent = Caffeine.newBuilder()
                .expireAfterWrite(window)
                .maximumSize(500)
                .build();
    }

    /** Desligado: nada é guardado. É o modo dos testes de unidade dos serviços. */
    public static RecentFallbackCache disabled() {
        return new RecentFallbackCache(Duration.ZERO);
    }

    /**
     * Dentro da janela de uma falha recente, devolve o fallback guardado sem
     * tocar no provedor. Fora dela, tenta o vivo; se ele terminar em
     * {@link NoLiveDataException}, calcula o fallback e o guarda na janela.
     */
    @SuppressWarnings("unchecked")
    public <V> Mono<V> liveOrFallback(String key, Supplier<Mono<V>> live,
            Function<NoLiveDataException, Mono<V>> fallback) {
        return Mono.defer(() -> {
            Object cached = recent.getIfPresent(key);
            if (cached != null) {
                return Mono.just((V) cached);
            }
            return live.get().onErrorResume(NoLiveDataException.class,
                    e -> fallback.apply(e).doOnNext(value -> recent.put(key, value)));
        });
    }

    /** Esquece todas as falhas recentes (testes que trocam o estado do provedor). */
    public void invalidateAll() {
        recent.invalidateAll();
    }
}
