package br.com.economize.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Data
public class Indicator {

    /**
     * Procedência do preço: true quando ele veio do último snapshot bom
     * (provedor fora do ar ou orçamento diário estourado) em vez de uma cotação
     * viva. Fica FORA do JSON de propósito — o contrato do /all consumido pelo
     * APK publicado não muda — e existe só para a informação atravessar o
     * provedor até o catálogo, que não pode rotular preço velho como LIVE.
     */
    @JsonIgnore
    private boolean stale;
    private String id;
    private String type;
    private String code;

    @JsonAlias("codein")
    private String codeIn;

    private String name;

    @JsonAlias("bid")
    private BigDecimal buy;

    @JsonAlias("ask")
    private BigDecimal sell;

    @JsonAlias({ "pctChange", "varBid" })
    private BigDecimal variation;

    private Double points;

    /**
     * Mínima e máxima do dia, quando a fonte as informa: a AwesomeAPI manda
     * {@code high}/{@code low} em toda cotação do /json/all e a Brapi,
     * {@code regularMarketDayHigh/Low} — os dois já chegavam e eram jogados
     * fora no parse. Aditivos e nuláveis: o card só desenha a régua do dia
     * quando os dois existem, e quem ignora os campos lê o /all de sempre.
     */
    @JsonAlias("high")
    private BigDecimal dayHigh;

    @JsonAlias("low")
    private BigDecimal dayLow;

    /**
     * Fechamentos dos últimos dias, do mais antigo ao mais recente — a linha
     * de tendência do card. Nunca custa requisição própria: vem na MESMA
     * resposta que já trazia o preço (Brapi com {@code range=5d}, série
     * semanal do BCE, série do Yahoo). Nula quando a fonte não traz série,
     * como o /json/all da AwesomeAPI.
     */
    private List<BigDecimal> sparkline;

    /**
     * De onde o número veio ("AwesomeAPI", "Frankfurter (BCE)", "CoinGecko",
     * "Brapi"...). Desde que a AwesomeAPI passou a estourar cota com frequência
     * o preço da Home pode sair de fontes diferentes ao longo do dia, e o app
     * mostra "atualizado às 10:32 · fonte" — sem isso o usuário não teria como
     * saber de onde é o dólar que está vendo.
     */
    private String source;

    /**
     * Momento a que a cotação se refere, como a fonte o informa (época da
     * AwesomeAPI, {@code last_updated_at} da CoinGecko, data de referência do
     * BCE). Quando o preço vem do snapshot este é o instante ORIGINAL, e não o
     * da leitura: é justamente o que diz ao usuário quão velho o número é.
     */
    private Instant asOf;

    /**
     * Época em segundos que a AwesomeAPI manda em cada cotação. Só entra
     * (WRITE_ONLY): o provedor a converte em {@link #asOf} e ela nunca sai no
     * JSON, para não duplicar a informação nem mudar o contrato do /all.
     */
    @JsonProperty(value = "timestamp", access = JsonProperty.Access.WRITE_ONLY)
    private String providerTimestamp;

    public BigDecimal getVariation() {
        return variation != null ? variation.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    /**
     * A variação como veio da fonte, sem o zero que o getter põe no lugar do
     * nulo. O contrato do /all precisa do zero; o overview do Mercado precisa
     * distinguir "não variou" de "a fonte não disse" — senão um índice sem
     * fechamento anterior sairia pintado de +0,00%. Não é getter de
     * propósito: Jackson não o serializa e o /all continua o mesmo.
     */
    public BigDecimal variationOrNull() {
        return variation != null ? variation.setScale(2, RoundingMode.HALF_UP) : null;
    }

    /**
     * Cópia marcada como stale. É cópia, e não mutação, porque o original mora
     * no snapshot compartilhado: marcá-lo no lugar contaminaria a mesma
     * instância para todo mundo. Os campos são copiados crus (o getter de
     * variação troca null por zero, e um null precisa continuar null).
     */
    public Indicator staleCopy() {
        Indicator copy = new Indicator();
        copy.id = this.id;
        copy.type = this.type;
        copy.code = this.code;
        copy.codeIn = this.codeIn;
        copy.name = this.name;
        copy.buy = this.buy;
        copy.sell = this.sell;
        copy.variation = this.variation;
        copy.points = this.points;
        copy.dayHigh = this.dayHigh;
        copy.dayLow = this.dayLow;
        copy.sparkline = this.sparkline;
        copy.source = this.source;
        copy.asOf = this.asOf;
        copy.stale = true;
        return copy;
    }
}
