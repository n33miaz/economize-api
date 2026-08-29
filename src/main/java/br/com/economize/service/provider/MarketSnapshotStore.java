package br.com.economize.service.provider;

import br.com.economize.dto.Indicator;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Guarda o último snapshot bom de cada provedor/ativo para servir como
 * stale-on-error: quando um provedor externo falha (429 de cota, timeout,
 * circuito aberto), cotação defasada é melhor do que lista vazia — sem isso o
 * "Mercado agora" da Home fica em esqueleto. TTL de 24h para nunca apresentar
 * preço de mais de um dia como se fosse atual.
 */
@Component
public class MarketSnapshotStore {

    private final Cache<String, List<Indicator>> snapshots = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24))
            .maximumSize(500)
            .build();

    public void save(String key, List<Indicator> indicators) {
        if (indicators != null && !indicators.isEmpty()) {
            snapshots.put(key, List.copyOf(indicators));
        }
    }

    /**
     * Tudo que sai daqui é, por definição, preço velho — então sai já marcado
     * como stale. É essa marca que impede o catálogo de anunciar como "LIVE" um
     * preço que na verdade veio do último snapshot bom.
     */
    public Optional<List<Indicator>> find(String key) {
        return Optional.ofNullable(snapshots.getIfPresent(key))
                .map(MarketSnapshotStore::asStale);
    }

    /**
     * Prefixo de chave para snapshots vindos de busca do usuário: eles servem
     * de stale para a própria busca, mas não podem entrar no agregado do
     * fallback — senão um ticker pesquisado horas antes apareceria no
     * "Mercado agora" como se fosse item da lista padrão.
     */
    public static final String SEARCH_PREFIX = "search:";

    /**
     * Todos os snapshots ainda válidos, agregados — usado pelo fallback do
     * circuit breaker, que não sabe quais chaves cada provedor usa.
     */
    public List<Indicator> findAll() {
        return snapshots.asMap().entrySet().stream()
                .filter(entry -> !entry.getKey().startsWith(SEARCH_PREFIX))
                .flatMap(entry -> asStale(entry.getValue()).stream())
                .toList();
    }

    private static List<Indicator> asStale(List<Indicator> indicators) {
        return indicators.stream().map(Indicator::staleCopy).toList();
    }
}
