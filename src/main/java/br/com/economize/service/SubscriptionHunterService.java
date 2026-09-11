package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.Category;
import br.com.economize.model.RecurringSeries;
import br.com.economize.model.User;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.RecurringSeriesRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O que você paga todo mês, e quanto isso é por ano — EC-203.
 *
 * <p><b>Por que o número anual.</b> Ninguém cancela uma assinatura de
 * R$ 23,90. Muita gente cancela uma de <b>R$ 286,80 por ano</b>. É o mesmo
 * dinheiro; o que muda é a unidade em que a decisão é tomada. O concorrente
 * lista assinaturas e mostra o valor mensal — a lista é bonita e não faz
 * ninguém agir.
 *
 * <p><b>O valor deste serviço está no FILTRO, não na detecção.</b> A detecção
 * já existe ({@code RecurrenceDetectionService}). Medi a regra ingênua contra
 * o extrato real do dono — "mesmo valor em três meses ou mais" — e ela achou
 * <b>24 candidatas</b>, das quais só <b>duas</b> são assinaturas de verdade
 * (Inter Cel R$ 30 em 16 meses e Spotify R$ 23,90). As outras 22:
 *
 * <ul>
 *   <li><b>Aplicação de CDB</b> de R$ 1,20 em oito meses — dinheiro trocando de
 *       gaveta (já marcado pelo EC-214);</li>
 *   <li><b>Saques</b> de R$ 20, R$ 40 e R$ 50 — valor redondo se repete por
 *       ser redondo, não por ser cobrança;</li>
 *   <li><b>Pix para pessoas</b>, inclusive para si mesmo — a mesada de R$ 30
 *       para a Alice não é assinatura;</li>
 *   <li><b>Passagem e mercadinho</b> de R$ 4 e R$ 6 — coincidência de preço
 *       baixo.</li>
 * </ul>
 *
 * <p>Uma lista com 22 linhas erradas em 24 não é uma ferramenta: é ruído com
 * cara de relatório, e ensina o usuário a não abrir a tela.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionHunterService {

    /**
     * Depois de quanto tempo sem cobrar uma assinatura vira pergunta.
     *
     * <p>Quarenta e cinco dias: uma mensal atrasada em quinze é atraso de
     * fatura; parada há um mês e meio ou foi cancelada e o app não sabe, ou
     * vai voltar a cobrar de surpresa. Nos dois casos a pessoa quer saber.
     */
    static final Duration SILENCIO = Duration.ofDays(45);

    /**
     * Piso de valor para uma cobrança entrar na caça.
     *
     * <p>Não é o piso de materialidade do EC-212 — aquele governa avisos, este
     * governa o que se chama de assinatura. R$ 5,00 mensais são R$ 60 por ano,
     * e abaixo disso o que o extrato do dono tem é passagem de ônibus e pão,
     * que se repetem sem serem cobrança.
     */
    static final BigDecimal PISO_MENSAL = new BigDecimal("5.00");

    private final RecurringSeriesRepository recurringSeriesRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    public Report huntFor(String email) {
        return huntFor(email, OffsetDateTime.now());
    }

    /** @param agora injetado para o teste mandar no relógio. */
    public Report huntFor(String email, OffsetDateTime agora) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
        Map<UUID, Category> categorias = new HashMap<>();
        categoryRepository.findVisibleTo(user.getId())
                .forEach(categoria -> categorias.put(categoria.getId(), categoria));

        List<Subscription> assinaturas = new ArrayList<>();
        int examinadas = 0;
        for (RecurringSeries serie : recurringSeriesRepository.findAllByUserId(user.getId())) {
            examinadas++;
            if (!ehAssinatura(serie)) continue;

            BigDecimal mensal = mensalDe(serie);
            BigDecimal anual = mensal.multiply(BigDecimal.valueOf(12));
            boolean parada = serie.getLastSeenAt() != null
                    && Duration.between(serie.getLastSeenAt(), agora).compareTo(SILENCIO) > 0;
            Category categoria = serie.getCategoryId() == null
                    ? null : categorias.get(serie.getCategoryId());

            assinaturas.add(new Subscription(
                    serie.getId(),
                    serie.getDisplayName() != null ? serie.getDisplayName() : serie.getMerchantKey(),
                    categoria != null ? categoria.getName() : null,
                    mensal, anual, serie.getOccurrences(),
                    serie.getFirstSeenAt(), serie.getLastSeenAt(), parada));
        }

        // Da mais cara por ano para a mais barata: é a ordem em que a decisão
        // de cancelar se toma
        assinaturas.sort(Comparator.comparing(Subscription::yearlyAmount).reversed());
        BigDecimal totalAnual = assinaturas.stream().map(Subscription::yearlyAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long paradas = assinaturas.stream().filter(Subscription::silent).count();

        log.info("Caça-assinaturas: {} de {} série(s) são assinatura, R$ {}/ano, {} parada(s), user={}",
                assinaturas.size(), examinadas, totalAnual, paradas, email);
        return new Report(examinadas, assinaturas.size(), totalAnual, (int) paradas, assinaturas);
    }

    /**
     * Esta série é uma assinatura?
     *
     * <p>Visível para o teste porque é a decisão inteira — e é onde as 22
     * falsas candidatas do extrato real são recusadas.
     */
    static boolean ehAssinatura(RecurringSeries serie) {
        if (serie.getFlow() != RecurringSeries.Flow.EXPENSE) return false;
        // Descartada pelo usuário nunca volta, nem por esta porta
        if (serie.isDismissed() || !serie.isActive()) return false;
        // Cadência que não é mensal nem trimestral não é assinatura: SEMANAL
        // é hábito (o mercado de sábado) e IRREGULAR é o contrário de cobrança
        if (serie.getCadence() != RecurringSeries.Cadence.MONTHLY
                && serie.getCadence() != RecurringSeries.Cadence.QUARTERLY) return false;
        // Valor variável não é assinatura: é conta de luz, e cancelar não é
        // uma opção que faça sentido oferecer
        if (serie.getAmountType() != RecurringSeries.AmountType.FIXED) return false;
        BigDecimal esperado = serie.getExpectedAmount();
        if (esperado == null) return false;
        if (mensalDe(serie).compareTo(PISO_MENSAL) < 0) return false;
        // Uma cobrança vista uma vez só não é recorrência provada
        return serie.getOccurrences() >= 2;
    }

    /**
     * O equivalente MENSAL da cobrança.
     *
     * <p>Trimestral dividida por três, e não por doze: o plano de R$ 90 a cada
     * três meses custa R$ 30 por mês e R$ 360 por ano, que é o número que
     * interessa. Dividir por doze diria R$ 7,50 e faria a assinatura mais cara
     * da lista parecer a mais barata.
     */
    private static BigDecimal mensalDe(RecurringSeries serie) {
        BigDecimal esperado = serie.getExpectedAmount().abs();
        return serie.getCadence() == RecurringSeries.Cadence.QUARTERLY
                ? esperado.divide(BigDecimal.valueOf(3), 2, java.math.RoundingMode.HALF_UP)
                : esperado;
    }

    /**
     * @param yearlyAmount o número que faz alguém cancelar
     * @param silent       não cobra há mais de 45 dias — ou acabou e o app não
     *                     sabe, ou vai voltar de surpresa
     */
    public record Subscription(UUID seriesId, String name, String category,
                               BigDecimal monthlyAmount, BigDecimal yearlyAmount,
                               int occurrences, OffsetDateTime firstSeenAt,
                               OffsetDateTime lastSeenAt, boolean silent) {
    }

    /**
     * @param seriesExamined quantas séries foram olhadas — o denominador que
     *                       mostra o quanto o filtro recusa
     * @param yearlyTotal    o que todas elas somam por ano
     */
    public record Report(int seriesExamined, int subscriptions, BigDecimal yearlyTotal,
                         int silentCount, List<Subscription> details) {
    }
}
