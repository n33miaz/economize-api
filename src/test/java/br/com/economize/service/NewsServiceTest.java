package br.com.economize.service;

import br.com.economize.dto.NewsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class NewsServiceTest {

    @Mock
    private WebClient webClient;

    @Mock
    @SuppressWarnings("rawtypes")
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    @SuppressWarnings("rawtypes")
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private NewsService newsService;

    @BeforeEach
    void setUp() {
        newsService = new NewsService(webClient);
    }

    private void mockWebClientSuccess(String rssXml) {
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(rssXml));
    }

    private void mockWebClientFailure() {
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.error(new RuntimeException("rss offline")));
    }

    @Test
    @DisplayName("Deve retornar notícias quando o RSS responder com sucesso")
    void shouldReturnNewsSuccessfully() {
        mockWebClientSuccess("""
                <?xml version="1.0" encoding="UTF-8" ?>
                <rss version="2.0">
                  <channel>
                    <title>InfoMoney</title>
                    <item>
                      <title>Mercado sobe hoje</title>
                      <description>Bolsa fecha em alta.</description>
                      <link>https://example.com/noticia</link>
                      <pubDate>Fri, 15 May 2026 12:00:00 GMT</pubDate>
                    </item>
                  </channel>
                </rss>
                """);

        Mono<NewsResponse> result = newsService.getTopHeadlines("br", "business");

        StepVerifier.create(result)
                .expectNextMatches(response -> response.getStatus().equals("ok")
                        && response.getTotalResults() == 3
                        && response.getArticles().get(0).getTitle().equals("Mercado sobe hoje"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Deve retornar lista vazia se os feeds RSS falharem")
    void shouldReturnEmptyListWhenFeedsFail() {
        mockWebClientFailure();

        Mono<NewsResponse> result = newsService.getTopHeadlines("br", "business");

        StepVerifier.create(result)
                .expectNextMatches(response -> response.getStatus().equals("ok")
                        && response.getTotalResults() == 0
                        && response.getArticles().isEmpty())
                .verifyComplete();
    }
}
