package br.com.economize.service;

import br.com.economize.dto.NewsArticle;
import br.com.economize.dto.NewsQuery;
import br.com.economize.dto.NewsResponse;
import br.com.economize.dto.Source;
import br.com.economize.service.news.NewsProvider;
import br.com.economize.service.news.NewsProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewsServiceTest {

    @Mock
    private NewsProviderRegistry registry;

    private NewsService newsService;

    @BeforeEach
    void setUp() {
        newsService = new NewsService(registry);
    }

    private static NewsArticle article(String title, String url, String publishedAt, String sourceName) {
        NewsArticle article = new NewsArticle();
        article.setTitle(title);
        article.setDescription("Descrição de " + title);
        article.setUrl(url);
        article.setPublishedAt(publishedAt);
        Source source = new Source();
        source.setName(sourceName);
        article.setSource(source);
        return article;
    }

    private static NewsProvider stubProvider(String id, String region, String category,
            NewsArticle... articles) {
        return new NewsProvider() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getName() {
                return id;
            }

            @Override
            public String getRegion() {
                return region;
            }

            @Override
            public String getCategory() {
                return category;
            }

            @Override
            public Flux<NewsArticle> fetch() {
                return Flux.fromArray(articles);
            }
        };
    }

    @Test
    @DisplayName("Deve agregar as fontes e ordenar por data de publicação decrescente")
    void shouldAggregateAndSortByDateDesc() {
        NewsProvider p1 = stubProvider("fonte-a", "br", "economia",
                article("Antiga", "https://a.test/1", "2026-08-10T08:00:00-03:00", "Fonte A"));
        NewsProvider p2 = stubProvider("fonte-b", "br", "economia",
                article("Recente", "https://b.test/2", "2026-08-14T09:00:00-03:00", "Fonte B"),
                article("Sem data", "https://b.test/3", null, "Fonte B"));

        when(registry.select(any(), any(), any())).thenReturn(List.of(p1, p2));

        Mono<NewsResponse> result = newsService.getTopHeadlines(NewsQuery.of(null, null, null, null, null));

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals("ok", response.getStatus());
                    assertEquals(3, response.getTotalResults());
                    assertEquals("Recente", response.getArticles().get(0).getTitle());
                    assertEquals("Antiga", response.getArticles().get(1).getTitle());
                    // sem data vai para o fim, sem estourar NPE na ordenação
                    assertEquals("Sem data", response.getArticles().get(2).getTitle());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Deve deduplicar artigos pelo link")
    void shouldDeduplicateByLink() {
        String sharedUrl = "https://agencia.test/materia";
        NewsProvider p1 = stubProvider("fonte-a", "br", "economia",
                article("Matéria original", sharedUrl, "2026-08-14T09:00:00-03:00", "Fonte A"));
        NewsProvider p2 = stubProvider("fonte-b", "br", "economia",
                article("Matéria replicada", sharedUrl, "2026-08-14T10:00:00-03:00", "Fonte B"));

        when(registry.select(any(), any(), any())).thenReturn(List.of(p1, p2));

        StepVerifier.create(newsService.getTopHeadlines(NewsQuery.of(null, null, null, null, null)))
                .assertNext(response -> assertEquals(1, response.getTotalResults()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Deve filtrar por texto (q) em título e descrição, sem case")
    void shouldFilterByTextQuery() {
        NewsProvider p1 = stubProvider("fonte-a", "br", "economia",
                article("PETROBRAS anuncia dividendos", "https://a.test/1", "2026-08-14T09:00:00-03:00",
                        "Fonte A"),
                article("Vale despenca", "https://a.test/2", "2026-08-14T08:00:00-03:00", "Fonte A"));

        when(registry.select(any(), any(), any())).thenReturn(List.of(p1));

        StepVerifier.create(newsService.getTopHeadlines(NewsQuery.of(null, null, null, "petrobras", null)))
                .assertNext(response -> {
                    assertEquals(1, response.getTotalResults());
                    assertEquals("PETROBRAS anuncia dividendos", response.getArticles().get(0).getTitle());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Deve limitar a quantidade de artigos quando limit for informado")
    void shouldLimitResults() {
        NewsProvider p1 = stubProvider("fonte-a", "br", "economia",
                article("Um", "https://a.test/1", "2026-08-14T09:00:00-03:00", "Fonte A"),
                article("Dois", "https://a.test/2", "2026-08-14T08:00:00-03:00", "Fonte A"),
                article("Três", "https://a.test/3", "2026-08-14T07:00:00-03:00", "Fonte A"));

        when(registry.select(any(), any(), any())).thenReturn(List.of(p1));

        StepVerifier.create(newsService.getTopHeadlines(NewsQuery.of(null, null, null, null, 2)))
                .assertNext(response -> {
                    assertEquals(2, response.getTotalResults());
                    assertEquals("Um", response.getArticles().get(0).getTitle());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Deve repassar os filtros de seleção de fontes ao registry")
    void shouldDelegateSourceSelectionToRegistry() {
        when(registry.select(any(), any(), any())).thenReturn(List.of());

        StepVerifier.create(newsService
                .getTopHeadlines(NewsQuery.of("infomoney, g1-economia", "BR", "cripto", null, null)))
                .assertNext(response -> assertEquals(0, response.getTotalResults()))
                .verifyComplete();

        verify(registry).select(Set.of("infomoney", "g1-economia"), "br", "cripto");
    }

    @Test
    @DisplayName("Categoria do contrato antigo (ex.: business) segue aceita e ignorada")
    void legacyCategoryShouldBeIgnored() {
        NewsQuery query = NewsQuery.of(null, null, "business", null, null);
        assertNull(query.category(), "categoria legada não pode virar filtro");

        NewsQuery blank = NewsQuery.of(" ", "", "  ", null, 0);
        assertNull(blank.sources());
        assertNull(blank.region());
        assertNull(blank.category());
        assertNull(blank.limit());
    }

    @Test
    @DisplayName("Falha catastrófica deve devolver resposta vazia bem formada")
    void shouldReturnEmptyResponseOnCatastrophicFailure() {
        NewsProvider broken = new NewsProvider() {
            @Override
            public String getId() {
                return "quebrada";
            }

            @Override
            public String getName() {
                return "Quebrada";
            }

            @Override
            public String getRegion() {
                return "br";
            }

            @Override
            public String getCategory() {
                return "economia";
            }

            @Override
            public Flux<NewsArticle> fetch() {
                return Flux.error(new RuntimeException("estouro inesperado"));
            }
        };
        when(registry.select(any(), any(), any())).thenReturn(List.of(broken));

        StepVerifier.create(newsService.getTopHeadlines(NewsQuery.of(null, null, null, null, null)))
                .assertNext(response -> {
                    assertEquals("ok", response.getStatus());
                    assertEquals(0, response.getTotalResults());
                })
                .verifyComplete();
    }
}
