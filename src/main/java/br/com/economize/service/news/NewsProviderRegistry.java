package br.com.economize.service.news;

import br.com.economize.config.NewsFeedsProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Set;

/**
 * Registry dos provedores de notícias disponíveis, montado a partir da
 * configuração de feeds. É a única porta de entrada do serviço para saber
 * quais fontes existem e selecioná-las por filtro.
 */
@Component
public class NewsProviderRegistry {

    private final List<NewsProvider> providers;

    public NewsProviderRegistry(NewsFeedsProperties properties,
            @Qualifier("rssWebClient") WebClient rssWebClient) {
        this.providers = properties.getFeeds().stream()
                .map(feed -> (NewsProvider) new RssNewsProvider(feed, rssWebClient,
                        properties.getFeedTimeout(), properties.getItemsPerFeed()))
                .toList();
    }

    public List<NewsProvider> getAll() {
        return providers;
    }

    /**
     * Seleciona provedores pelos filtros informados; filtro nulo não restringe.
     */
    public List<NewsProvider> select(Set<String> sourceIds, String region, String category) {
        return providers.stream()
                .filter(p -> sourceIds == null || sourceIds.contains(p.getId()))
                .filter(p -> region == null || region.equalsIgnoreCase(p.getRegion()))
                .filter(p -> category == null || category.equalsIgnoreCase(p.getCategory()))
                .toList();
    }
}
