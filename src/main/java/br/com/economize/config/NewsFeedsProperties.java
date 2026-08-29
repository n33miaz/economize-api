package br.com.economize.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Catálogo de feeds RSS agregados pelo endpoint de notícias. Os defaults vivem
 * aqui em código; qualquer feed pode ser sobrescrito/adicionado via properties
 * (economize.news.feeds[N].id/.name/.url/.region/.category) sem recompilar.
 */
@Data
@Component
@ConfigurationProperties(prefix = "economize.news")
public class NewsFeedsProperties {

    /** Tempo máximo de espera por feed: um feed lento não pode segurar o agregado. */
    private Duration feedTimeout = Duration.ofSeconds(10);

    /** Máximo de artigos aproveitados de cada feed. */
    private int itemsPerFeed = 10;

    private List<Feed> feeds = List.of(
            Feed.of("infomoney", "InfoMoney", "https://www.infomoney.com.br/feed/", "br", "economia"),
            Feed.of("investnews", "InvestNews", "https://investnews.com.br/feed/", "br", "economia"),
            Feed.of("g1-economia", "G1 Economia", "https://g1.globo.com/rss/g1/economia/", "br", "economia"),
            Feed.of("exame", "Exame", "https://exame.com/feed/", "br", "economia"),
            Feed.of("seudinheiro", "Seu Dinheiro", "https://www.seudinheiro.com/feed/", "br", "economia"),
            Feed.of("moneytimes", "Money Times", "https://www.moneytimes.com.br/feed/", "br", "mercados"),
            // BM&C News: o host com "www" falha o handshake TLS; usar sem "www"
            Feed.of("bmcnews", "BM&C News", "https://bmcnews.com.br/feed/", "br", "mercados"),
            // CNN Brasil: o feed da editoria de economia responde 404; só o feed
            // raiz (todas as editorias) está vivo — por isso categoria "geral"
            Feed.of("cnn-brasil", "CNN Brasil", "https://www.cnnbrasil.com.br/feed/", "br", "geral"),
            Feed.of("yahoo-finance", "Yahoo Finance", "https://finance.yahoo.com/news/rssindex", "global",
                    "mercados"),
            Feed.of("coindesk", "CoinDesk", "https://www.coindesk.com/arc/outboundfeeds/rss/", "global",
                    "cripto"),
            Feed.of("cointelegraph", "Cointelegraph", "https://cointelegraph.com/rss", "global", "cripto"));

    @Data
    public static class Feed {
        private String id;
        private String name;
        private String url;
        /** Região da fonte: "br" ou "global". */
        private String region = "br";
        /** Categoria editorial: economia, mercados, cripto, tecnologia, geral... */
        private String category = "economia";

        public static Feed of(String id, String name, String url, String region, String category) {
            Feed feed = new Feed();
            feed.setId(id);
            feed.setName(name);
            feed.setUrl(url);
            feed.setRegion(region);
            feed.setCategory(category);
            return feed;
        }
    }
}
