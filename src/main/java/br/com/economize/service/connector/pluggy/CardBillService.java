package br.com.economize.service.connector.pluggy;

import br.com.economize.model.CardBill;
import br.com.economize.model.ConnectorAccount;
import br.com.economize.model.PluggyItem;
import br.com.economize.model.User;
import br.com.economize.repository.CardBillRepository;
import br.com.economize.repository.ConnectorAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * As faturas FECHADAS que o provedor entrega, guardadas ao lado das que o app
 * deduz.
 *
 * <p><b>Por que existe.</b> {@code CardInvoiceService} monta a fatura
 * recortando os lançamentos pelo dia de fechamento e somando o que temos —
 * dedução honesta, e declarada como tal no contrato, mas que só enxerga o que
 * chegou até nós. Medido na conta do dono em 21/09/2026: o app mostrava
 * R$ 775,67 num cartão cuja fatura fechada no banco foi de R$ 2.311,49. A
 * diferença são compras que o conector não trouxe, e o app não tinha como
 * saber que faltava algo.
 *
 * <p><b>O que isto NÃO faz.</b> Não substitui a fatura deduzida e não cria
 * lançamento nenhum: o Pluggy entrega o total da fatura, não a lista de
 * compras dela. Guardar as duas é o que permite dizer "o banco fechou em X e
 * eu só enxergo Y" — e é esse aviso, não o número sozinho, que resolve o
 * problema que ele relatou.
 *
 * <p><b>Nunca derruba a sincronização de extrato.</b> Fatura é enriquecimento;
 * extrato é o produto. Uma falha aqui vira log e a sync segue — foi assim que
 * o investimento ficou meses sem aparecer sem ninguém notar, e a lição é não
 * acoplar o opcional ao essencial.
 */
@Slf4j
@Service
// Mesma condição do PluggyClient e do PluggySyncService: sem conector ligado
// não existe de quem ler fatura, e um bean pedindo um cliente que não nasceu
// derruba o contexto inteiro — foi o que aconteceu ao esquecer esta linha.
@ConditionalOnProperty(name = "economize.pluggy.enabled", havingValue = "true")
@RequiredArgsConstructor
public class CardBillService {

    private final PluggyClient pluggyClient;
    private final CardBillRepository cardBillRepository;
    private final ConnectorAccountRepository accountRepository;

    /**
     * Lê e grava as faturas de todos os cartões das conexões dadas.
     *
     * @return quantas faturas foram criadas ou atualizadas
     */
    @Transactional
    public int sync(User user, String apiKey, List<PluggyItem> items) {
        int gravadas = 0;
        for (PluggyItem item : items) {
            for (Map<String, Object> conta : pluggyClient.accounts(apiKey, item.getItemId())) {
                if (!"CREDIT".equalsIgnoreCase(String.valueOf(conta.get("type")))) continue;
                String idNoProvedor = texto(conta.get("id"));
                if (idNoProvedor == null) continue;

                // A conta precisa JÁ existir do nosso lado: quem a cria é o sync
                // de extrato, e uma fatura sem cartão não significa nada
                Optional<ConnectorAccount> nossa =
                        accountRepository.findByUserIdAndProviderAccountId(user.getId(), idNoProvedor);
                if (nossa.isEmpty()) continue;

                try {
                    gravadas += gravar(user, nossa.get(), pluggyClient.bills(apiKey, idNoProvedor));
                } catch (Exception e) {
                    // Conta de cartão sem fatura publicada responde 4xx; emissor
                    // fora do ar também. Nenhum dos dois é motivo para o extrato
                    // inteiro falhar
                    log.warn("Faturas não lidas para a conta {} (conector=\"{}\"): {}",
                            nossa.get().getId(), item.getConnectorName(), e.getMessage());
                }
            }
        }
        if (gravadas > 0) {
            log.info("Pluggy: {} fatura(s) do provedor gravada(s) para user={}", gravadas, user.getId());
        }
        return gravadas;
    }

    private int gravar(User user, ConnectorAccount conta, List<Map<String, Object>> faturas) {
        int n = 0;
        OffsetDateTime agora = OffsetDateTime.now();
        for (Map<String, Object> f : faturas) {
            String externo = texto(f.get("id"));
            if (externo == null) continue;

            CardBill fatura = cardBillRepository
                    .findByAccountIdAndExternalId(conta.getId(), externo)
                    .orElseGet(() -> CardBill.builder()
                            .user(user)
                            .accountId(conta.getId())
                            .externalId(externo)
                            .build());

            // Fatura em aberto muda de total até fechar: a re-leitura
            // SOBRESCREVE, sempre. Não há nada do nosso lado para preservar
            fatura.setClosingDate(data(f.get("billClosingDate")));
            fatura.setDueDate(data(f.get("dueDate")));
            fatura.setTotalAmount(decimal(f.get("totalAmount")));
            fatura.setMinimumPayment(decimal(f.get("minimumPaymentAmount")));
            fatura.setFinanceCharges(somaDeEncargos(f.get("financeCharges")));
            fatura.setCurrency(texto(f.get("totalAmountCurrencyCode")));
            fatura.setAllowsInstallments(Boolean.TRUE.equals(f.get("allowsInstallments")));
            fatura.setSyncedAt(agora);
            cardBillRepository.save(fatura);
            n++;
        }
        return n;
    }

    /**
     * Os encargos vêm como lista de itens ({@code juros}, {@code multa}, …). O
     * app quer um número — a lista inteira é detalhe que nenhuma tela mostra.
     */
    private BigDecimal somaDeEncargos(Object bruto) {
        if (!(bruto instanceof List<?> lista) || lista.isEmpty()) return null;
        BigDecimal total = BigDecimal.ZERO;
        for (Object item : lista) {
            if (item instanceof Map<?, ?> mapa) {
                BigDecimal v = decimal(mapa.get("amount"));
                if (v != null) total = total.add(v);
            }
        }
        return total;
    }

    private static String texto(Object valor) {
        if (valor == null) return null;
        String s = String.valueOf(valor).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static BigDecimal decimal(Object valor) {
        if (valor instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        String s = texto(valor);
        if (s == null) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** O Pluggy manda ISO com hora ({@code 2026-09-07T00:00:00.000Z}); o dia basta. */
    private static LocalDate data(Object valor) {
        String s = texto(valor);
        if (s == null) return null;
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
