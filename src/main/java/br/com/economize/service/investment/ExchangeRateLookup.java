package br.com.economize.service.investment;

import br.com.economize.dto.Indicator;
import br.com.economize.service.IndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Quantos reais vale uma unidade de moeda estrangeira, agora.
 *
 * <p>Existe para o resumo de investimentos, que somava posição em dólar como
 * se o número já fosse real. A cotação é a MESMA da Home — o indicador
 * {@code currency_USD} que o {@link IndicatorService} já busca, guarda em
 * cache e serve do último snapshot bom quando o provedor cai — e a leitura é
 * a mesma que o {@code ForeignQuoteService} faz para o {@code priceBrl}: o
 * {@code buy} do indicador, quando positivo. Assim o patrimônio e a cotação
 * da ação estrangeira nunca usam dois dólares diferentes.
 *
 * <p><b>Cotação velha vai declarada.</b> Quando o provedor cai, o que chega é
 * o último snapshot bom (até 7 dias). Ele serve — dólar de terça é muito
 * melhor que dólar nenhum —, desde que saia marcado: por isso a resposta é um
 * {@link Rate}, com a data da medição e a marca de snapshot.
 *
 * <p><b>Sem cotação, sem número.</b> Vazio é resposta prevista (provedor fora
 * e nenhum snapshot, moeda que a Home não acompanha) e quem chama decide o
 * que fazer — nunca um câmbio inventado, nunca 1.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeRateLookup {

    /**
     * Teto de espera. Os indicadores vêm do cache quase sempre; quando não
     * vêm, o resumo não pode ficar pendurado num provedor lento — depois
     * disso a posição sai dos totais e é sinalizada.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final IndicatorService indicatorService;

    /**
     * A cotação e a IDADE dela.
     *
     * @param value reais por unidade da moeda
     * @param asOf  quando o provedor mediu esse preço; nulo se ele não disse
     * @param stale true quando veio do último snapshot bom (provedor fora do
     *              ar) em vez de uma cotação viva — pode ter até 7 dias
     */
    public record Rate(BigDecimal value, Instant asOf, boolean stale) {
    }


    /** A cotação de {@code currency} (ISO de 3 letras) em reais, com a idade dela; vazio se não há. */
    public Optional<Rate> rate(String currency) {
        if (currency == null || currency.isBlank()) return Optional.empty();
        // Este método BLOQUEIA, e quem o chama já está fora do event loop (o
        // controller manda o resumo para o boundedElastic). Se um dia alguém
        // chamar de dentro de uma cadeia reativa, block() estouraria ali:
        // melhor responder "sem cotação", que é um caminho já tratado
        if (Schedulers.isInNonBlockingThread()) return Optional.empty();
        String id = "currency_" + currency.trim().toUpperCase(Locale.ROOT);
        try {
            List<Indicator> indicators = indicatorService.getAllIndicators().block(TIMEOUT);
            if (indicators == null) return Optional.empty();
            return indicators.stream()
                    .filter(indicator -> id.equals(indicator.getId()))
                    .filter(indicator -> indicator.getBuy() != null && indicator.getBuy().signum() > 0)
                    // a idade viaja com o número: snapshot velho é melhor que
                    // nada, mas só se quem lê o resumo souber que é velho
                    .map(indicator -> new Rate(indicator.getBuy(), indicator.getAsOf(), indicator.isStale()))
                    .findFirst();
        } catch (RuntimeException e) {
            log.warn("Câmbio [{}] indisponível para o resumo de investimentos: {}", id, e.getMessage());
            return Optional.empty();
        }
    }
}
