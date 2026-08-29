package br.com.economize.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catálogo ampliado de ativos (EC-099) e os pesos da ordenação.
 *
 * <p>
 * Só os ativos da B3 vivem aqui em código. Moedas e cripto NÃO entram nesta
 * lista porque a AwesomeAPI devolve o universo inteiro em uma única chamada
 * (/all): descobrir sai de graça e nunca fica desatualizado. Já a Brapi cobra
 * uma requisição por ticker contra uma cota de ~1.000/dia, então descobrir
 * ativo por ativo custaria o dia inteiro — por isso o metadado da B3 é estático
 * e versionado com o projeto, e a cotação só é buscada para a página exibida.
 *
 * <p>
 * Todos os campos são sobrescrevíveis por properties (prefixo
 * economize.catalog), no mesmo espírito de {@link NewsFeedsProperties}: dá para
 * corrigir nome, remover ticker deslistado ou reordenar relevância sem
 * recompilar.
 */
@Data
@Component
@ConfigurationProperties(prefix = "economize.catalog")
public class MarketCatalogProperties {

    /** Versão do catálogo, devolvida na resposta para o app invalidar cache local. */
    private String version = "2026.08.1";

    /** Itens por página quando o cliente não manda limit. */
    private int defaultPageSize = 15;

    /** Teto de itens por página; pedido acima disso é reduzido, não rejeitado. */
    private int maxPageSize = 50;

    /**
     * Teto de cotações novas buscadas na Brapi por requisição de página. Segura
     * tanto a cota diária quanto o número de conexões simultâneas ao provedor.
     */
    private int maxQuotesPerPage = 20;

    /**
     * Janela em que a ordenação fica congelada. Enquanto o usuário rola, a
     * ordem não pode mudar embaixo dele — senão item repete ou some entre
     * páginas. Ordem nova só na janela seguinte.
     */
    private Duration rankWindow = Duration.ofMinutes(10);

    /**
     * Validade da cotação de ticker que só existe no catálogo ampliado. Maior
     * que os 10 min do cache de /all de propósito: ativo de cauda não precisa
     * de frescor de minuto, e triplicar a janela triplica quantos ativos cabem
     * na mesma cota diária.
     */
    private Duration quoteTtl = Duration.ofMinutes(30);

    /**
     * Teto diário de requisições à Brapi para a demanda ABERTA — rolagem do
     * catálogo e busca do usuário somadas. É o que sobra depois de reservar o
     * pior caso do /all; ver a aritmética inteira em
     * {@link br.com.economize.service.catalog.QuoteBudget}.
     */
    private int dailyQuoteBudget = 500;

    /**
     * Teto diário de requisições à Brapi somando TUDO (/all, catálogo e busca).
     * Fica abaixo da cota do plano (~1.000/dia) de propósito: a folga absorve
     * retry e a imprecisão de um contador por instância.
     */
    private int dailyProviderBudget = 850;

    /**
     * Peso do sinal do usuário. Alto o bastante para que favorito sempre venha
     * antes de qualquer item de mercado, sem precisar de ordenação em duas
     * passadas.
     */
    private int favoriteBoost = 1000;

    /** Relevância de mercado de quem não tem rank declarado (cauda longa). */
    private int defaultRank = 20;

    /** Multiplicador da variação recente no score de tendência. */
    private double momentumWeight = 2.0;

    /**
     * Teto do |variação%| considerado. Sem teto, um ativo ilíquido com salto de
     * 40% num dia passaria na frente de Ibovespa e dólar.
     */
    private double momentumCap = 10.0;

    /**
     * Relevância de mercado por id de indicador, para o que é descoberto em
     * tempo de execução (moedas e cripto da AwesomeAPI). O que não está aqui
     * cai em {@link #defaultRank} — é o caso das variantes de turismo, que
     * devem mesmo afundar na lista.
     */
    private Map<String, Integer> rankOverrides = defaultRankOverrides();

    /** Catálogo estático da B3: ações, FIIs, ETFs, BDRs e índices. */
    private List<Asset> assets = defaultAssets();

    @Data
    public static class Asset {
        /** Ticker como a Brapi espera (índice vem com "^"). */
        private String symbol;
        private String name;
        /** "stock" ou "index" — mesmos valores que os provedores já emitem. */
        private String type = "stock";
        /** Recorte para a UI: acoes, fiis, etfs, bdrs, indices. */
        private String segment = "acoes";
        /** Relevância de mercado, 0..100. */
        private int rank;

        public static Asset of(String symbol, String name, String segment, int rank) {
            return build(symbol, name, "stock", segment, rank);
        }

        public static Asset index(String symbol, String name, int rank) {
            return build(symbol, name, "index", "indices", rank);
        }

        private static Asset build(String symbol, String name, String type, String segment, int rank) {
            Asset asset = new Asset();
            asset.setSymbol(symbol);
            asset.setName(name);
            asset.setType(type);
            asset.setSegment(segment);
            asset.setRank(rank);
            return asset;
        }
    }

    private static Map<String, Integer> defaultRankOverrides() {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        ranks.put("currency_USD", 99);
        ranks.put("currency_EUR", 95);
        ranks.put("crypto_BTC", 94);
        ranks.put("currency_GBP", 88);
        ranks.put("crypto_ETH", 85);
        ranks.put("currency_ARS", 74);
        ranks.put("currency_JPY", 72);
        ranks.put("currency_CAD", 68);
        ranks.put("currency_AUD", 66);
        ranks.put("currency_CHF", 65);
        ranks.put("currency_CNY", 64);
        ranks.put("crypto_LTC", 45);
        ranks.put("crypto_XRP", 45);
        return ranks;
    }

    /**
     * Ranks calibrados para que a primeira página caia sobre o que já está
     * cotado no agregado de /all (moedas inteiras e os tickers padrão da
     * Brapi): abrir o catálogo custa perto de zero de cota, e o gasto só
     * aparece conforme o usuário rola.
     */
    private static List<Asset> defaultAssets() {
        List<Asset> assets = new ArrayList<>();

        assets.add(Asset.index("^BVSP", "Ibovespa", 100));

        // Ações — grandes capitalizações
        assets.add(Asset.of("PETR4", "Petrobras PN", "acoes", 96));
        assets.add(Asset.of("VALE3", "Vale ON", "acoes", 95));
        assets.add(Asset.of("ITUB4", "Itaú Unibanco PN", "acoes", 94));
        assets.add(Asset.of("BBDC4", "Bradesco PN", "acoes", 92));
        assets.add(Asset.of("BBAS3", "Banco do Brasil ON", "acoes", 91));
        assets.add(Asset.of("ABEV3", "Ambev ON", "acoes", 90));
        assets.add(Asset.of("ITSA4", "Itaúsa PN", "acoes", 89));
        assets.add(Asset.of("B3SA3", "B3 ON", "acoes", 87));
        assets.add(Asset.of("WEGE3", "WEG ON", "acoes", 86));
        assets.add(Asset.of("PETR3", "Petrobras ON", "acoes", 82));
        assets.add(Asset.of("ITUB3", "Itaú Unibanco ON", "acoes", 74));
        assets.add(Asset.of("BBDC3", "Bradesco ON", "acoes", 73));
        assets.add(Asset.of("SANB11", "Santander Brasil UNT", "acoes", 78));
        assets.add(Asset.of("BPAC11", "BTG Pactual UNT", "acoes", 80));
        assets.add(Asset.of("SUZB3", "Suzano ON", "acoes", 79));
        assets.add(Asset.of("RENT3", "Localiza ON", "acoes", 78));
        assets.add(Asset.of("PRIO3", "PRIO ON", "acoes", 77));
        assets.add(Asset.of("RAIL3", "Rumo ON", "acoes", 72));
        assets.add(Asset.of("GGBR4", "Gerdau PN", "acoes", 76));
        assets.add(Asset.of("GOAU4", "Metalúrgica Gerdau PN", "acoes", 66));
        assets.add(Asset.of("CSNA3", "CSN ON", "acoes", 68));
        assets.add(Asset.of("USIM5", "Usiminas PNA", "acoes", 65));
        assets.add(Asset.of("BRAP4", "Bradespar PN", "acoes", 62));
        assets.add(Asset.of("KLBN11", "Klabin UNT", "acoes", 71));
        assets.add(Asset.of("EMBR3", "Embraer ON", "acoes", 81));
        assets.add(Asset.of("AZUL4", "Azul PN", "acoes", 55));
        assets.add(Asset.of("GOLL4", "Gol PN", "acoes", 52));
        assets.add(Asset.of("CVCB3", "CVC Brasil ON", "acoes", 48));
        assets.add(Asset.of("LREN3", "Lojas Renner ON", "acoes", 70));
        assets.add(Asset.of("MGLU3", "Magazine Luiza ON", "acoes", 69));
        assets.add(Asset.of("ASAI3", "Assaí ON", "acoes", 67));
        assets.add(Asset.of("CRFB3", "Carrefour Brasil ON", "acoes", 58));
        assets.add(Asset.of("PCAR3", "Grupo Pão de Açúcar ON", "acoes", 50));
        assets.add(Asset.of("NTCO3", "Natura ON", "acoes", 63));
        assets.add(Asset.of("JBSS3", "JBS ON", "acoes", 75));
        assets.add(Asset.of("MRFG3", "Marfrig ON", "acoes", 59));
        assets.add(Asset.of("BEEF3", "Minerva ON", "acoes", 54));
        assets.add(Asset.of("BRFS3", "BRF ON", "acoes", 64));
        assets.add(Asset.of("SMTO3", "São Martinho ON", "acoes", 56));
        assets.add(Asset.of("SLCE3", "SLC Agrícola ON", "acoes", 55));
        assets.add(Asset.of("AGRO3", "BrasilAgro ON", "acoes", 44));
        assets.add(Asset.of("ELET3", "Eletrobras ON", "acoes", 83));
        assets.add(Asset.of("ELET6", "Eletrobras PNB", "acoes", 70));
        assets.add(Asset.of("CMIG4", "Cemig PN", "acoes", 68));
        assets.add(Asset.of("CPLE6", "Copel PNB", "acoes", 62));
        assets.add(Asset.of("CPFE3", "CPFL Energia ON", "acoes", 60));
        assets.add(Asset.of("EGIE3", "Engie Brasil ON", "acoes", 66));
        assets.add(Asset.of("ENGI11", "Energisa UNT", "acoes", 61));
        assets.add(Asset.of("EQTL3", "Equatorial ON", "acoes", 73));
        assets.add(Asset.of("TAEE11", "Taesa UNT", "acoes", 63));
        assets.add(Asset.of("TRPL4", "ISA Energia PN", "acoes", 53));
        assets.add(Asset.of("NEOE3", "Neoenergia ON", "acoes", 52));
        assets.add(Asset.of("AURE3", "Auren Energia ON", "acoes", 46));
        assets.add(Asset.of("SBSP3", "Sabesp ON", "acoes", 72));
        assets.add(Asset.of("CSMG3", "Copasa ON", "acoes", 45));
        assets.add(Asset.of("SAPR11", "Sanepar UNT", "acoes", 49));
        assets.add(Asset.of("VIVT3", "Telefônica Brasil ON", "acoes", 69));
        assets.add(Asset.of("TIMS3", "TIM ON", "acoes", 65));
        assets.add(Asset.of("RADL3", "Raia Drogasil ON", "acoes", 74));
        assets.add(Asset.of("HAPV3", "Hapvida ON", "acoes", 64));
        assets.add(Asset.of("RDOR3", "Rede D'Or ON", "acoes", 76));
        assets.add(Asset.of("FLRY3", "Fleury ON", "acoes", 51));
        assets.add(Asset.of("HYPE3", "Hypera ON", "acoes", 60));
        assets.add(Asset.of("QUAL3", "Qualicorp ON", "acoes", 38));
        assets.add(Asset.of("ODPV3", "Odontoprev ON", "acoes", 42));
        assets.add(Asset.of("CYRE3", "Cyrela ON", "acoes", 58));
        assets.add(Asset.of("MRVE3", "MRV ON", "acoes", 47));
        assets.add(Asset.of("EZTC3", "EZTEC ON", "acoes", 43));
        assets.add(Asset.of("DIRR3", "Direcional ON", "acoes", 41));
        assets.add(Asset.of("TRIS3", "Trisul ON", "acoes", 32));
        assets.add(Asset.of("MULT3", "Multiplan ON", "acoes", 59));
        assets.add(Asset.of("IGTI11", "Iguatemi UNT", "acoes", 48));
        assets.add(Asset.of("ALOS3", "Allos ON", "acoes", 57));
        assets.add(Asset.of("CCRO3", "CCR ON", "acoes", 62));
        assets.add(Asset.of("ECOR3", "EcoRodovias ON", "acoes", 44));
        assets.add(Asset.of("STBP3", "Santos Brasil ON", "acoes", 46));
        assets.add(Asset.of("RAIZ4", "Raízen PN", "acoes", 56));
        assets.add(Asset.of("VBBR3", "Vibra Energia ON", "acoes", 67));
        assets.add(Asset.of("UGPA3", "Ultrapar ON", "acoes", 66));
        assets.add(Asset.of("CSAN3", "Cosan ON", "acoes", 63));
        assets.add(Asset.of("TOTS3", "TOTVS ON", "acoes", 71));
        assets.add(Asset.of("LWSA3", "Locaweb ON", "acoes", 36));
        assets.add(Asset.of("POSI3", "Positivo ON", "acoes", 33));
        assets.add(Asset.of("INTB3", "Intelbras ON", "acoes", 40));
        assets.add(Asset.of("BBSE3", "BB Seguridade ON", "acoes", 70));
        assets.add(Asset.of("PSSA3", "Porto Seguro ON", "acoes", 61));
        assets.add(Asset.of("CXSE3", "Caixa Seguridade ON", "acoes", 57));
        assets.add(Asset.of("IRBR3", "IRB Brasil ON", "acoes", 35));
        assets.add(Asset.of("GMAT3", "Grupo Mateus ON", "acoes", 43));
        assets.add(Asset.of("PETZ3", "Petz ON", "acoes", 34));
        assets.add(Asset.of("ALPA4", "Alpargatas PN", "acoes", 39));
        assets.add(Asset.of("GRND3", "Grendene ON", "acoes", 37));
        assets.add(Asset.of("VULC3", "Vulcabras ON", "acoes", 33));
        assets.add(Asset.of("CEAB3", "C&A Modas ON", "acoes", 36));
        assets.add(Asset.of("VIVA3", "Vivara ON", "acoes", 38));
        assets.add(Asset.of("YDUQ3", "YDUQS ON", "acoes", 40));
        assets.add(Asset.of("COGN3", "Cogna ON", "acoes", 39));
        assets.add(Asset.of("ANIM3", "Ânima ON", "acoes", 28));
        assets.add(Asset.of("SIMH3", "Simpar ON", "acoes", 35));
        assets.add(Asset.of("VAMO3", "Vamos ON", "acoes", 37));
        assets.add(Asset.of("RAPT4", "Randon PN", "acoes", 41));
        assets.add(Asset.of("TUPY3", "Tupy ON", "acoes", 34));
        assets.add(Asset.of("MYPK3", "Iochpe-Maxion ON", "acoes", 31));
        assets.add(Asset.of("POMO4", "Marcopolo PN", "acoes", 42));
        assets.add(Asset.of("LEVE3", "Mahle Metal Leve ON", "acoes", 30));
        assets.add(Asset.of("FRAS3", "Fras-le ON", "acoes", 29));
        assets.add(Asset.of("KEPL3", "Kepler Weber ON", "acoes", 30));
        assets.add(Asset.of("UNIP6", "Unipar PNB", "acoes", 32));
        assets.add(Asset.of("BRKM5", "Braskem PNA", "acoes", 45));
        assets.add(Asset.of("DXCO3", "Dexco ON", "acoes", 38));
        assets.add(Asset.of("MDIA3", "M. Dias Branco ON", "acoes", 44));
        assets.add(Asset.of("CAML3", "Camil ON", "acoes", 27));
        assets.add(Asset.of("WIZC3", "Wiz Co ON", "acoes", 26));

        // ETFs
        assets.add(Asset.of("BOVA11", "iShares Ibovespa ETF", "etfs", 88));
        assets.add(Asset.of("IVVB11", "iShares S&P 500 ETF", "etfs", 86));
        assets.add(Asset.of("SMAL11", "iShares Small Cap ETF", "etfs", 64));
        assets.add(Asset.of("BOVV11", "It Now Ibovespa ETF", "etfs", 58));
        assets.add(Asset.of("PIBB11", "It Now IBrX-50 ETF", "etfs", 55));
        assets.add(Asset.of("DIVO11", "It Now Dividendos ETF", "etfs", 54));
        assets.add(Asset.of("HASH11", "Hashdex Nasdaq Crypto ETF", "etfs", 60));
        assets.add(Asset.of("IMAB11", "It Now IMA-B ETF", "etfs", 50));
        assets.add(Asset.of("XFIX11", "It Now IFIX ETF", "etfs", 49));
        assets.add(Asset.of("NASD11", "Investo Nasdaq-100 ETF", "etfs", 52));

        // Fundos imobiliários
        assets.add(Asset.of("MXRF11", "Maxi Renda FII", "fiis", 84));
        assets.add(Asset.of("KNRI11", "Kinea Renda Imobiliária FII", "fiis", 68));
        assets.add(Asset.of("HGLG11", "CSHG Logística FII", "fiis", 67));
        assets.add(Asset.of("XPML11", "XP Malls FII", "fiis", 66));
        assets.add(Asset.of("HGRU11", "CSHG Renda Urbana FII", "fiis", 63));
        assets.add(Asset.of("VISC11", "Vinci Shopping Centers FII", "fiis", 61));
        assets.add(Asset.of("BTLG11", "BTG Pactual Logística FII", "fiis", 62));
        assets.add(Asset.of("KNCR11", "Kinea Rendimentos Imobiliários FII", "fiis", 65));
        assets.add(Asset.of("KNIP11", "Kinea Índices de Preços FII", "fiis", 60));
        assets.add(Asset.of("MALL11", "Malls Brasil Plural FII", "fiis", 52));
        assets.add(Asset.of("XPLG11", "XP Log FII", "fiis", 57));
        assets.add(Asset.of("HGBS11", "CSHG Brasil Shopping FII", "fiis", 55));
        assets.add(Asset.of("BCFF11", "BTG Pactual Fundo de Fundos FII", "fiis", 53));
        assets.add(Asset.of("RBRF11", "RBR Alpha FoF FII", "fiis", 46));
        assets.add(Asset.of("IRDM11", "Iridium Recebíveis FII", "fiis", 56));
        assets.add(Asset.of("HGCR11", "CSHG Recebíveis Imobiliários FII", "fiis", 54));
        assets.add(Asset.of("CPTS11", "Capitânia Securities FII", "fiis", 51));
        assets.add(Asset.of("VRTA11", "Fator Verità FII", "fiis", 44));
        assets.add(Asset.of("HSML11", "HSI Malls FII", "fiis", 47));
        assets.add(Asset.of("GGRC11", "GGR Covepi Renda FII", "fiis", 43));
        assets.add(Asset.of("TRXF11", "TRX Real Estate FII", "fiis", 45));
        assets.add(Asset.of("VILG11", "Vinci Logística FII", "fiis", 48));
        assets.add(Asset.of("ALZR11", "Alianza Trust Renda Imobiliária FII", "fiis", 42));
        assets.add(Asset.of("PVBI11", "VBI Prime Properties FII", "fiis", 41));
        assets.add(Asset.of("RZTR11", "Riza Terrax FII", "fiis", 40));

        // BDRs
        assets.add(Asset.of("AAPL34", "Apple BDR", "bdrs", 72));
        assets.add(Asset.of("MSFT34", "Microsoft BDR", "bdrs", 71));
        assets.add(Asset.of("NVDC34", "NVIDIA BDR", "bdrs", 70));
        assets.add(Asset.of("AMZO34", "Amazon BDR", "bdrs", 69));
        assets.add(Asset.of("GOGL34", "Alphabet BDR", "bdrs", 68));
        assets.add(Asset.of("META34", "Meta Platforms BDR", "bdrs", 66));
        assets.add(Asset.of("TSLA34", "Tesla BDR", "bdrs", 67));
        assets.add(Asset.of("NFLX34", "Netflix BDR", "bdrs", 60));
        assets.add(Asset.of("ROXO34", "Nu Holdings BDR", "bdrs", 64));
        assets.add(Asset.of("XPBR31", "XP Inc BDR", "bdrs", 59));
        assets.add(Asset.of("DISB34", "Disney BDR", "bdrs", 52));
        assets.add(Asset.of("COCA34", "Coca-Cola BDR", "bdrs", 51));
        assets.add(Asset.of("MCDC34", "McDonald's BDR", "bdrs", 48));
        assets.add(Asset.of("JPMC34", "JPMorgan BDR", "bdrs", 55));
        assets.add(Asset.of("BOAC34", "Bank of America BDR", "bdrs", 47));
        assets.add(Asset.of("VISA34", "Visa BDR", "bdrs", 54));
        assets.add(Asset.of("MSCD34", "Mastercard BDR", "bdrs", 53));
        assets.add(Asset.of("PFIZ34", "Pfizer BDR", "bdrs", 43));
        assets.add(Asset.of("JNJB34", "Johnson & Johnson BDR", "bdrs", 44));
        assets.add(Asset.of("WALM34", "Walmart BDR", "bdrs", 46));
        assets.add(Asset.of("IBMB34", "IBM BDR", "bdrs", 41));
        assets.add(Asset.of("ORCL34", "Oracle BDR", "bdrs", 45));
        assets.add(Asset.of("INTC34", "Intel BDR", "bdrs", 40));
        assets.add(Asset.of("AMDD34", "AMD BDR", "bdrs", 49));
        assets.add(Asset.of("PYPL34", "PayPal BDR", "bdrs", 39));
        assets.add(Asset.of("UBER34", "Uber BDR", "bdrs", 42));
        assets.add(Asset.of("TSMC34", "TSMC BDR", "bdrs", 38));
        assets.add(Asset.of("BABA34", "Alibaba BDR", "bdrs", 37));

        return assets;
    }
}
