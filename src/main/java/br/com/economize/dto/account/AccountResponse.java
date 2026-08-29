package br.com.economize.dto.account;

import br.com.economize.model.ConnectorAccount;

import java.util.UUID;

/**
 * Origem de lançamento como o app a apresenta (EC-113). A listagem de transações
 * devolve só o {@code accountId} — é aqui que ele ganha nome, instituição e
 * tipo, num recurso pequeno o bastante para o app carregar uma vez e casar em
 * memória, em vez de repetir os mesmos rótulos em cada linha do extrato.
 */
public record AccountResponse(
        UUID id,
        // "Ultravioleta ····1234" — rótulo pronto para a tela
        String name,
        // BANK ou CREDIT_CARD. Só CREDIT_CARD abre fatura.
        ConnectorAccount.AccountType type,
        String institution,
        // Nulos quando o provedor não informou: nesse caso a fatura é recortada
        // pelo mês do calendário, e a resposta da fatura declara que foi assim
        Integer statementClosingDay,
        Integer statementDueDay,
        // A conexão que traz esta conta ainda está vinculada? Falso = origem
        // histórica: os lançamentos continuam, mas nada novo entra por ela
        boolean linked
) {
    public static AccountResponse from(ConnectorAccount account) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getInstitution(),
                account.getStatementClosingDay(),
                account.getStatementDueDay(),
                account.getPluggyItemId() != null);
    }
}
