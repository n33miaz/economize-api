package br.com.economize.service.news;

import br.com.economize.dto.NewsArticle;
import reactor.core.publisher.Flux;

/**
 * Fonte de notícias plugável. O agregado não conhece RSS nem HTTP: qualquer
 * implementação (RSS, API proprietária...) entra no registry pelos metadados.
 */
public interface NewsProvider {

    /** Identificador estável usado no filtro ?sources= e nas preferências do app. */
    String getId();

    String getName();

    /** Região da fonte: "br" ou "global". */
    String getRegion();

    /** Categoria editorial: economia, mercados, cripto, tecnologia, geral... */
    String getCategory();

    /** Artigos mais recentes da fonte. Nunca deve propagar erro: falha => Flux vazio. */
    Flux<NewsArticle> fetch();
}
