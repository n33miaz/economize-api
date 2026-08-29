package br.com.economize.service;

import br.com.economize.dto.NewsArticle;
import br.com.economize.dto.NewsQuery;
import br.com.economize.dto.NewsResponse;
import br.com.economize.dto.NewsSourceInfo;
import br.com.economize.dto.NewsSourcesResponse;
import br.com.economize.service.news.NewsProvider;
import br.com.economize.service.news.NewsProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
public class NewsService {

    private final NewsProviderRegistry registry;

    // publishedAt sem data (ou inválida) vai para o fim da lista, nunca para NPE
    private static final Comparator<NewsArticle> BY_PUBLISHED_DESC = Comparator
            .comparing(NewsService::publishedInstant, Comparator.reverseOrder());

    public NewsService(NewsProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * Agrega as manchetes das fontes selecionadas pelos filtros do
     * {@link NewsQuery}. A chave do cache é o próprio query normalizado:
     * requisições com filtros diferentes não compartilham entrada.
     */
    @Cacheable("news")
    public Mono<NewsResponse> getTopHeadlines(NewsQuery query) {
        List<NewsProvider> selected = registry.select(query.sourceIds(), query.region(), query.category());
        log.info("Agregando noticias de {} fontes (filtros: {})", selected.size(), query);

        return Flux.fromIterable(selected)
                .flatMap(NewsProvider::fetch)
                .filter(article -> matchesText(article, query.q()))
                // fontes republicam umas às outras; o link identifica o artigo
                .distinct(NewsService::dedupeKey)
                .sort(BY_PUBLISHED_DESC)
                .transform(flux -> query.limit() != null ? flux.take(query.limit()) : flux)
                .collectList()
                .map(NewsService::toResponse)
                .onErrorResume(e -> {
                    log.error("Falha catastrófica ao buscar notícias: {}", e.getMessage());
                    return Mono.just(toResponse(List.of()));
                });
    }

    /** Fontes disponíveis para o app montar a configuração de preferências. */
    public NewsSourcesResponse getSources() {
        List<NewsSourceInfo> sources = registry.getAll().stream()
                .map(p -> new NewsSourceInfo(p.getId(), p.getName(), p.getRegion(), p.getCategory()))
                .toList();
        return new NewsSourcesResponse("ok", sources);
    }

    private static NewsResponse toResponse(List<NewsArticle> articles) {
        NewsResponse response = new NewsResponse();
        response.setStatus("ok");
        response.setTotalResults(articles.size());
        response.setArticles(articles);
        return response;
    }

    private static boolean matchesText(NewsArticle article, String q) {
        if (q == null) {
            return true;
        }
        return containsIgnoreCase(article.getTitle(), q) || containsIgnoreCase(article.getDescription(), q);
    }

    private static boolean containsIgnoreCase(String text, String lowerCaseTerm) {
        return text != null && text.toLowerCase().contains(lowerCaseTerm);
    }

    private static String dedupeKey(NewsArticle article) {
        if (article.getUrl() != null && !article.getUrl().isBlank()) {
            return article.getUrl();
        }
        String sourceName = article.getSource() != null ? article.getSource().getName() : "";
        return sourceName + "|" + article.getTitle();
    }

    private static Instant publishedInstant(NewsArticle article) {
        try {
            return OffsetDateTime.parse(article.getPublishedAt()).toInstant();
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }
}
