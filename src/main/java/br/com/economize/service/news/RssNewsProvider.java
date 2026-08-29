package br.com.economize.service.news;

import br.com.economize.config.NewsFeedsProperties;
import br.com.economize.dto.NewsArticle;
import br.com.economize.dto.Source;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.StringReader;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Implementação genérica de {@link NewsProvider} para qualquer feed RSS/Atom,
 * parametrizada pelos metadados vindos de {@link NewsFeedsProperties}.
 */
@Slf4j
public class RssNewsProvider implements NewsProvider {

    private final NewsFeedsProperties.Feed feed;
    private final WebClient webClient;
    private final Duration timeout;
    private final int itemsPerFeed;

    public RssNewsProvider(NewsFeedsProperties.Feed feed, WebClient webClient,
            Duration timeout, int itemsPerFeed) {
        this.feed = feed;
        this.webClient = webClient;
        this.timeout = timeout;
        this.itemsPerFeed = itemsPerFeed;
    }

    @Override
    public String getId() {
        return feed.getId();
    }

    @Override
    public String getName() {
        return feed.getName();
    }

    @Override
    public String getRegion() {
        return feed.getRegion();
    }

    @Override
    public String getCategory() {
        return feed.getCategory();
    }

    @Override
    public Flux<NewsArticle> fetch() {
        return webClient.get()
                .uri(feed.getUrl())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                // parse do XML é bloqueante; sai do event loop do Netty
                .publishOn(Schedulers.boundedElastic())
                .flatMapMany(this::parseRss)
                .onErrorResume(e -> {
                    // um feed fora do ar nunca derruba o agregado
                    log.warn("Erro ao buscar RSS da fonte {}: {}", feed.getId(), e.getMessage());
                    return Flux.empty();
                });
    }

    private Flux<NewsArticle> parseRss(String xml) {
        try {
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed syndFeed = input.build(new StringReader(xml));

            List<NewsArticle> articles = syndFeed.getEntries().stream()
                    .limit(itemsPerFeed)
                    .map(this::mapToNewsArticle)
                    .toList();

            return Flux.fromIterable(articles);
        } catch (Exception e) {
            log.warn("Erro ao fazer parse do RSS da fonte {}: {}", feed.getId(), e.getMessage());
            return Flux.empty();
        }
    }

    private NewsArticle mapToNewsArticle(SyndEntry entry) {
        NewsArticle article = new NewsArticle();
        article.setTitle(entry.getTitle());

        // Limpa tags HTML da descrição
        String description = entry.getDescription() != null
                ? entry.getDescription().getValue().replaceAll("<[^>]*>", "").trim()
                : "";
        // Limita o tamanho da descrição
        if (description.length() > 150) {
            description = description.substring(0, 147) + "...";
        }
        article.setDescription(description);

        article.setUrl(entry.getLink());
        article.setAuthor(entry.getAuthor());

        if (entry.getPublishedDate() != null) {
            article.setPublishedAt(entry.getPublishedDate().toInstant()
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }

        Source source = new Source();
        source.setId(feed.getId());
        source.setName(feed.getName());
        article.setSource(source);

        if (entry.getEnclosures() != null && !entry.getEnclosures().isEmpty()) {
            article.setUrlToImage(entry.getEnclosures().get(0).getUrl());
        }

        return article;
    }
}
