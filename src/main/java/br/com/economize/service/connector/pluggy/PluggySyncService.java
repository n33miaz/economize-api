package br.com.economize.service.connector.pluggy;

import br.com.economize.model.User;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.BankStatementService;
import br.com.economize.service.statement.parser.ParsedTransaction;
import br.com.economize.service.statement.parser.StatementFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Sincroniza as contas conectadas no Meu Pluggy para dentro do MESMO pipeline
 * do upload de extrato: motor de categorização, dedup por id do Pluggy e
 * reconciliação — um extrato importado depois do sync (ou o contrário) não
 * duplica nada. v1 lê só contas BANK; cartão de crédito fica para o EC-072.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "economize.pluggy.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PluggySyncService {

    private final PluggyClient pluggyClient;
    private final UserRepository userRepository;
    private final BankStatementService bankStatementService;

    @Value("${economize.pluggy.item-ids}")
    private String itemIdsRaw;

    // As credenciais e os itens são da aplicação, não de cada usuário: sem este
    // dono declarado, qualquer conta registrada puxaria o extrato bancário de
    // quem configurou o conector. Vínculo por usuário fica para o EC-072.
    @Value("${economize.pluggy.owner-email:}")
    private String ownerEmail;

    public Map<String, Object> status(String email) {
        boolean owner = isOwner(email);
        return Map.of(
                "enabled", true,
                "owner", owner,
                "configured", owner && pluggyClient.isConfigured() && !itemIds().isEmpty(),
                "itemCount", owner ? itemIds().size() : 0);
    }

    public BankStatementService.ImportResult sync(String email, int days) {
        if (!isOwner(email)) {
            throw new IllegalArgumentException(
                    "Conector Pluggy indisponível para esta conta — defina PLUGGY_OWNER_EMAIL com o e-mail do dono das credenciais");
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));
        List<String> items = itemIds();
        if (!pluggyClient.isConfigured() || items.isEmpty()) {
            throw new IllegalArgumentException(
                    "Conector Pluggy sem credenciais — defina PLUGGY_CLIENT_ID, PLUGGY_CLIENT_SECRET e PLUGGY_ITEM_IDS");
        }

        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.minusDays(Math.max(1, days));
        String apiKey = pluggyClient.authenticate();

        List<ParsedTransaction> parsed = new ArrayList<>();
        for (String itemId : items) {
            for (Map<String, Object> account : pluggyClient.accounts(apiKey, itemId)) {
                if (!"BANK".equalsIgnoreCase(String.valueOf(account.get("type")))) continue;
                String accountId = String.valueOf(account.get("id"));
                for (Map<String, Object> tx : pluggyClient.transactions(apiKey, accountId, from, to)) {
                    ParsedTransaction mapped = mapTransaction(tx);
                    if (mapped != null) parsed.add(mapped);
                }
            }
        }
        log.info("Pluggy sync: {} transações na janela {}..{} para user={}", parsed.size(), from, to, email);
        return bankStatementService.importFromConnector(user, "Meu Pluggy", StatementFormat.PLUGGY, parsed);
    }

    private ParsedTransaction mapTransaction(Map<String, Object> tx) {
        Object id = tx.get("id");
        Object amountRaw = tx.get("amount");
        Object dateRaw = tx.get("date");
        if (id == null || amountRaw == null || dateRaw == null) return null;

        BigDecimal amount = new BigDecimal(String.valueOf(amountRaw));
        String type;
        if (amount.signum() != 0) {
            // mesmo critério dos parsers: o sinal é a fonte confiável
            type = amount.signum() < 0 ? "DEBIT" : "CREDIT";
        } else {
            type = "DEBIT".equalsIgnoreCase(String.valueOf(tx.get("type"))) ? "DEBIT" : "CREDIT";
        }

        return ParsedTransaction.builder()
                .externalId("PLUGGY-" + id)
                .type(type)
                .amount(amount)
                .description(String.valueOf(tx.getOrDefault("description", "")))
                .date(parseDate(String.valueOf(dateRaw)))
                .build();
    }

    private OffsetDateTime parseDate(String raw) {
        try {
            return OffsetDateTime.parse(raw);
        } catch (Exception e) {
            // Pluggy também devolve datas curtas YYYY-MM-DD dependendo do conector
            return LocalDate.parse(raw.substring(0, 10)).atStartOfDay().atOffset(ZoneOffset.UTC);
        }
    }

    private boolean isOwner(String email) {
        return ownerEmail != null && !ownerEmail.isBlank()
                && ownerEmail.trim().equalsIgnoreCase(email);
    }

    private List<String> itemIds() {
        if (itemIdsRaw == null || itemIdsRaw.isBlank()) return List.of();
        return Arrays.stream(itemIdsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
