package br.com.economize.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.math.BigDecimal;
import java.math.RoundingMode;

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

    public BigDecimal getVariation() {
        return variation != null ? variation.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
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
        copy.stale = true;
        return copy;
    }
}