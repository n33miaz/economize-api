package br.com.economize.service.news;

import br.com.economize.config.NewsFeedsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({ "unchecked", "rawtypes" })
class RssNewsProviderTest {

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private RssNewsProvider provider;

    private static final String VALID_RSS = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <rss version="2.0">
              <channel>
                <title>Feed Teste</title>
                <item>
                  <title>Mercado sobe hoje</title>
                  <description>&lt;p&gt;Bolsa fecha em alta.&lt;/p&gt;</description>
                  <link>https://example.test/noticia-1</link>
                  <pubDate>Fri, 14 Aug 2026 12:00:00 GMT</pubDate>
                </item>
                <item>
                  <title>Dólar cai</title>
                  <description>Moeda recua.</description>
                  <link>https://example.test/noticia-2</link>
                  <pubDate>Fri, 14 Aug 2026 11:00:00 GMT</pubDate>
                </item>
                <item>
                  <title>Terceira notícia</title>
                  <description>Excede o limite por feed.</description>
                  <link>https://example.test/noticia-3</link>
                  <pubDate>Fri, 14 Aug 2026 10:00:00 GMT</pubDate>
                </item>
              </channel>
            </rss>
            """;

    @BeforeEach
    void setUp() {
        NewsFeedsProperties.Feed feed = NewsFeedsProperties.Feed.of(
                "feed-teste", "Feed Teste", "https://example.test/rss", "br", "economia");
        provider = new RssNewsProvider(feed, webClient, Duration.ofMillis(300), 2);

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    @DisplayName("Deve parsear o RSS, preencher a fonte e respeitar o limite por feed")
    void shouldParseRssAndApplyItemLimit() {
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(VALID_RSS));

        StepVerifier.create(provider.fetch().collectList())
                .assertNext(articles -> {
                    assertEquals(2, articles.size(), "itemsPerFeed=2 deve cortar o terceiro item");
                    assertEquals("Mercado sobe hoje", articles.get(0).getTitle());
                    assertEquals("Bolsa fecha em alta.", articles.get(0).getDescription());
                    assertEquals("https://example.test/noticia-1", articles.get(0).getUrl());
                    assertEquals("feed-teste", articles.get(0).getSource().getId());
                    assertEquals("Feed Teste", articles.get(0).getSource().getName());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Erro HTTP do feed deve virar Flux vazio, sem propagar")
    void shouldReturnEmptyOnHttpError() {
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.error(new RuntimeException("410 Gone")));

        StepVerifier.create(provider.fetch())
                .verifyComplete();
    }

    @Test
    @DisplayName("XML inválido deve virar Flux vazio, sem propagar")
    void shouldReturnEmptyOnInvalidXml() {
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("<html>não sou RSS</html>"));

        StepVerifier.create(provider.fetch())
                .verifyComplete();
    }

    @Test
    @DisplayName("Feed lento deve estourar o timeout e virar Flux vazio")
    void shouldTimeoutSlowFeed() {
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.never());

        StepVerifier.create(provider.fetch())
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }
}
