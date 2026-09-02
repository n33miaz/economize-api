package br.com.economize.service.provider;

import br.com.economize.dto.indicator.AssetDetail;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * As janelas de variação de um ativo (EC-103), a partir da série diária.
 *
 * <p>Aritmética pura, sem rede: é ela que decide o que a tela mostra em "7 dias"
 * e "no ano", e é o tipo de conta que só se confere com teste — um erro de um
 * dia na referência muda o número sem quebrar nada.
 *
 * <p><b>Regra que atravessa tudo:</b> janela sem dado devolve variação NULA, e
 * nunca zero. Papel listado há duas semanas não tem 30 dias de histórico, e
 * escrever "0,00%" ali afirmaria estabilidade onde não há informação.
 */
public final class AssetWindowCalculator {

    private AssetWindowCalculator() {
    }

    /** Um ponto da série: o fechamento de um pregão. */
    public record Close(LocalDate date, BigDecimal price) {
    }

    /**
     * As janelas na ordem em que a tela as lê: do mais curto para o mais longo.
     *
     * @param dayChangePct variação do dia informada pelo provedor — ela é a
     *                     autoridade sobre "hoje", porque considera o preço
     *                     intradiário, que a série de fechamentos não tem
     */
    public static List<AssetDetail.ChangeWindow> windows(BigDecimal price,
                                                         BigDecimal dayChangePct,
                                                         List<Close> series,
                                                         LocalDate today) {
        List<AssetDetail.ChangeWindow> windows = new ArrayList<>();
        windows.add(new AssetDetail.ChangeWindow("24h", "Hoje", dayChangePct, null, null));

        NavigableMap<LocalDate, BigDecimal> byDate = new TreeMap<>();
        for (Close close : series) {
            if (close != null && close.date() != null && close.price() != null
                    && close.price().signum() > 0) {
                byDate.put(close.date(), close.price());
            }
        }

        windows.add(window("7d", "7 dias", price, byDate, today.minusDays(7)));
        windows.add(window("30d", "30 dias", price, byDate, today.minusDays(30)));
        // O ano começa no dia 1º, mas a bolsa não abre nele: a referência é o
        // primeiro pregão do ano, e não "o fechamento de 31/12" — que pertence
        // ao ano passado e faria o YTD começar com a variação de outro período
        windows.add(windowFromFirstOfYear(price, byDate, today));
        return windows;
    }

    private static AssetDetail.ChangeWindow window(String key, String label, BigDecimal price,
                                                   NavigableMap<LocalDate, BigDecimal> byDate,
                                                   LocalDate target) {
        // `floorEntry`: fim de semana e feriado não têm pregão, então a
        // referência é o último fechamento ATÉ a data alvo. Exigir o dia exato
        // deixaria a janela vazia sempre que caísse num sábado
        var entry = byDate.floorEntry(target);
        if (entry == null || price == null || price.signum() <= 0) {
            return new AssetDetail.ChangeWindow(key, label, null, null, null);
        }
        return new AssetDetail.ChangeWindow(key, label,
                changePct(price, entry.getValue()), entry.getValue(), entry.getKey());
    }

    private static AssetDetail.ChangeWindow windowFromFirstOfYear(
            BigDecimal price, NavigableMap<LocalDate, BigDecimal> byDate, LocalDate today) {
        var entry = byDate.ceilingEntry(LocalDate.of(today.getYear(), 1, 1));
        if (entry == null || entry.getKey().getYear() != today.getYear()
                || price == null || price.signum() <= 0) {
            return new AssetDetail.ChangeWindow("ytd", "No ano", null, null, null);
        }
        return new AssetDetail.ChangeWindow("ytd", "No ano",
                changePct(price, entry.getValue()), entry.getValue(), entry.getKey());
    }

    private static BigDecimal changePct(BigDecimal price, BigDecimal reference) {
        if (reference == null || reference.signum() <= 0) return null;
        return price.subtract(reference)
                .multiply(BigDecimal.valueOf(100))
                .divide(reference, 2, RoundingMode.HALF_UP);
    }

    /**
     * Onde o preço está dentro da faixa de 52 semanas, de 0 a 1.
     *
     * <p>Nulo quando falta um extremo ou quando máxima e mínima coincidem: numa
     * faixa de largura zero, "posição" não significa nada, e devolver 0 ou 1
     * faria a régua desenhar o preço colado numa ponta por acidente.
     */
    public static BigDecimal rangePosition(BigDecimal price, BigDecimal low, BigDecimal high) {
        if (price == null || low == null || high == null) return null;
        BigDecimal width = high.subtract(low);
        if (width.signum() <= 0) return null;
        BigDecimal position = price.subtract(low)
                .divide(width, 4, RoundingMode.HALF_UP);
        // O preço de agora pode estar FORA da faixa do provedor: ela é apurada
        // periodicamente e uma máxima nova ainda não entrou nela. Grudar na
        // ponta é melhor do que uma régua com o marcador fora do trilho
        if (position.signum() < 0) return BigDecimal.ZERO;
        return position.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : position;
    }
}
