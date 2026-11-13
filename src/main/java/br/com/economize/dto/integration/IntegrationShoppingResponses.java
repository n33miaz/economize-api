package br.com.economize.dto.integration;

import br.com.economize.dto.analytics.IncomePatternResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * As respostas das leituras de integração (V44) — o contrato com o sistema que
 * consome, separado dos DTOs do app para que um não prenda o outro.
 *
 * <p>Duas convenções valem em tudo o que é NOVO aqui:
 * <ul>
 * <li>instante é {@link Instant}: sai em UTC, ISO-8601, com {@code Z};</li>
 * <li>dinheiro é inteiro em CENTAVOS, e o nome do campo termina em
 * {@code Cents} — quem consome não precisa adivinhar a unidade nem lidar com
 * fração binária.</li>
 * </ul>
 * Campo nulo quer dizer "não sei", nunca zero — a regra do resto do projeto.
 *
 * <p><b>Os nomes dos records são longos de propósito.</b> O contrato OpenAPI
 * nomeia cada schema pelo nome simples da classe: um {@code Advice} aqui
 * tomaria o lugar do {@code Advice} do padrão de renda no arquivo versionado,
 * e o contrato do app mudaria sem que o app tivesse mudado.
 */
public final class IntegrationShoppingResponses {

    private IntegrationShoppingResponses() {
    }

    /**
     * O conselho de compra.
     *
     * @param generatedAt   quando esta resposta foi montada
     * @param incomePattern exatamente o que {@code GET /api/v1/analytics/income-pattern}
     *                      devolve — o contrato de lá, sem tradução (valores em reais)
     * @param free          quanto sobra do próximo salário; nulo nunca — os campos
     *                      dele é que vêm nulos quando não há salário confirmado
     */
    public record IntegrationAdvice(
            Instant generatedAt,
            IncomePatternResponse incomePattern,
            FreeAfterSalary free
    ) {
    }

    /**
     * "Quanto está livre", como {@code GET /api/v1/income/committed} já calcula:
     * o salário esperado menos as contas recorrentes do ciclo que ele abre.
     *
     * @param salaryKnown falso = não há salário confirmado com dia de pagamento;
     *                    {@code freeCents} vem nulo
     * @param freeCents   o que sobra do próximo pagamento; pode ser negativo
     */
    public record FreeAfterSalary(
            boolean salaryKnown,
            LocalDate salaryDate,
            Integer daysUntilSalary,
            Long expectedSalaryCents,
            Long committedBeforeSalaryCents,
            Long committedAfterSalaryCents,
            Long freeCents
    ) {
    }

    /**
     * Uma página do histórico de compras.
     *
     * @param nextCursor o que mandar em {@code cursor} para a página seguinte;
     *                   vem SEMPRE que a página traz alguma compra, inclusive na
     *                   última — guardá-lo é o que permite buscar depois só o
     *                   que chegou de novo. Nulo apenas em página vazia
     * @param hasMore    há mais compras além desta página neste momento
     */
    public record IntegrationTripPage(
            List<IntegrationTrip> trips,
            String nextCursor,
            boolean hasMore
    ) {
    }

    /**
     * @param id                o id do servidor: estável e único entre todas as compras
     * @param clientId          o id que o aparelho gerou; único por dono
     * @param own               verdadeiro = aberta pelo dono da chave; falso = de
     *                          outra pessoa da casa, que a compartilhou
     * @param receiptTotalCents o total da nota, quando informado ou lido
     * @param itemsTotalCents   a soma do corredor: quantidade × preço dos itens pegos
     * @param paidTotalCents    o que saiu de verdade: a nota quando há, senão a soma
     * @param receiptKey        a chave de 44 dígitos do cupom, quando a nota foi lida
     * @param items             só os itens vivos — removidos não vêm
     */
    public record IntegrationTrip(
            UUID id,
            String clientId,
            UUID ownerId,
            boolean own,
            String storeName,
            String status,
            Instant startedAt,
            Instant closedAt,
            Long budgetCents,
            Long receiptTotalCents,
            long itemsTotalCents,
            long paidTotalCents,
            String receiptKey,
            String receiptIssuerCnpj,
            UUID reconciledTransactionId,
            String notes,
            List<IntegrationTripItem> items,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /**
     * @param name           como a pessoa digitou
     * @param normalizedName sem acento, minúsculo, espaços colapsados — a chave
     *                       do histórico de preços
     * @param quantity       decimal (até três casas): 1.5 é um quilo e meio
     * @param unitPriceCents nulo enquanto não há preço anotado
     * @param lineTotalCents quantidade × preço, arredondado ao centavo a partir
     *                       do preço gravado (que tem quatro casas)
     * @param promoNote      a promoção anotada ("leve 3 pague 2"), texto livre
     * @param checked        falso = a pessoa viu o preço e não levou
     */
    public record IntegrationTripItem(
            UUID id,
            String clientId,
            String name,
            String normalizedName,
            BigDecimal quantity,
            Long unitPriceCents,
            Long lineTotalCents,
            String promoNote,
            boolean checked,
            UUID addedBy,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
