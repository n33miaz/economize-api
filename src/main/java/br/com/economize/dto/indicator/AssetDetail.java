package br.com.economize.dto.indicator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * O detalhe enriquecido de um ativo (EC-103).
 *
 * <p>Sai de UMA chamada ao provedor: o mesmo {@code /quote} que já dava o preço
 * traz também a faixa de 52 semanas — que estava sendo lida e descartada — e,
 * com {@code range=1y&interval=1d}, a série diária do ano. As janelas de
 * variação são calculadas daqui, sem uma segunda requisição contra a cota.
 */
public record AssetDetail(
        String code,
        String name,
        BigDecimal price,
        /** Variação do dia, como o provedor a informa. */
        BigDecimal dayChangePct,
        /** Extremos do último ano; nulos quando o provedor não os informa. */
        BigDecimal fiftyTwoWeekHigh,
        BigDecimal fiftyTwoWeekLow,
        /**
         * Onde o preço de hoje está DENTRO da faixa de 52 semanas, de 0 (na
         * mínima) a 1 (na máxima). É o que a tela precisa para desenhar a régua
         * sem refazer a conta — e é nulo quando falta extremo ou a faixa é
         * degenerada (máxima igual à mínima), caso em que "posição" não
         * significaria nada.
         */
        BigDecimal rangePosition,
        List<ChangeWindow> windows,
        /**
         * Preço servido de snapshot antigo porque a cota do dia acabou ou o
         * provedor falhou. A tela precisa dizer isso — número velho sem aviso é
         * pior que número ausente.
         */
        boolean stale
) {

    /**
     * Uma janela de variação. {@code changePct} nulo é resposta legítima: papel
     * recém-listado não tem 30 dias de histórico, e devolver zero ali afirmaria
     * estabilidade onde não há dado.
     */
    public record ChangeWindow(
            String key,
            String label,
            BigDecimal changePct,
            /** Preço de referência de onde a variação foi medida. */
            BigDecimal fromPrice,
            LocalDate fromDate
    ) {
    }
}
