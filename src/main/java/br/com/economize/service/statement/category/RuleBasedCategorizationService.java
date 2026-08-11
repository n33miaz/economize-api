package br.com.economize.service.statement.category;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RuleBasedCategorizationService {

    private static final Map<TransactionCategory, List<String>> RULES = new LinkedHashMap<>();

    static {
        RULES.put(TransactionCategory.FOOD, List.of("ifood", "rappi", "restaurante", "padaria", "supermercado", "mercado", "lanchonete", "uber eats"));
        RULES.put(TransactionCategory.TRANSPORT, List.of("uber", "99 ", "99app", "gasolina", "posto", "metro", "metrô", "ônibus", "onibus", "estacionamento"));
        RULES.put(TransactionCategory.LEISURE, List.of("netflix", "spotify", "cinema", "ingresso", "show", "steam", "playstation", "xbox"));
        RULES.put(TransactionCategory.HEALTH, List.of("farmacia", "farmácia", "drogaria", "consulta", "clinica", "clínica", "hospital", "exame"));
        RULES.put(TransactionCategory.EDUCATION, List.of("escola", "faculdade", "curso", "udemy", "coursera", "livraria", "livro"));
        RULES.put(TransactionCategory.HOUSING, List.of("aluguel", "condominio", "condomínio", "iptu", "imobiliaria", "imobiliária"));
        RULES.put(TransactionCategory.SHOPPING, List.of("amazon", "mercado livre", "magalu", "shopee", "americanas", "shopping"));
        RULES.put(TransactionCategory.UTILITIES, List.of("energia", "luz", "agua", "água", "internet", "telefone", "celular", "vivo", "claro", "tim"));
        RULES.put(TransactionCategory.INCOME, List.of("salario", "salário", "pagamento recebido", "credito", "crédito recebido", "rendimento"));
        RULES.put(TransactionCategory.TRANSFER, List.of("pix", "ted", "doc", "transferencia", "transferência"));
    }

    public TransactionCategory categorize(String description, String type) {
        if (description == null) return TransactionCategory.OTHER;
        String lower = description.toLowerCase();
        for (Map.Entry<TransactionCategory, List<String>> entry : RULES.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (lower.contains(keyword)) return entry.getKey();
            }
        }
        if ("CREDIT".equalsIgnoreCase(type)) return TransactionCategory.INCOME;
        return TransactionCategory.OTHER;
    }
}
