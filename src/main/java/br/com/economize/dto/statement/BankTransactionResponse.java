package br.com.economize.dto.statement;

import br.com.economize.model.BankTransaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record BankTransactionResponse(
        UUID id,
        String transactionId,
        String type,
        BigDecimal amount,
        /*
         * O que a tela mostra: apelido quando existe, senão o descritivo do
         * banco (EC-094). O campo continua sendo este para que TODA listagem já
         * escrita passe a respeitar o apelido sem mudar de contrato.
         */
        String description,
        // O descritivo cru do banco, sempre presente — é ele que o modal de
        // detalhes exibe ao lado do apelido, e sem ele o usuário perderia a
        // referência para conferir o lançamento no app do banco
        String originalDescription,
        // Nulo quando não há apelido: é o que diz ao app se o campo "description"
        // veio renomeado e pré-preenche o formulário de edição
        String displayAlias,
        OffsetDateTime date,
        UUID categoryId,
        BankTransaction.ReviewStatus reviewStatus,
        BankTransaction.CategorizedBy categorizedBy,
        BigDecimal confidence,
        String normalizedDescription,
        UUID uploadId,
        /*
         * EC-113: a ORIGEM da linha — de qual conta bancária ou cartão de crédito
         * ela veio. Só o id: nome, instituição e tipo saem de
         * GET /api/v1/accounts, que o app carrega uma vez e casa em memória em
         * vez de receber os mesmos rótulos repetidos em cada linha do extrato.
         *
         * NULO é um valor legítimo e permanente, não um erro: é o histórico
         * anterior à V16 e é todo lançamento de upload manual de arquivo, que não
         * tem conta de provedor. O app deve apresentá-lo como "origem não
         * informada". Campo somado ao contrato, nenhum removido.
         */
        UUID accountId,
        // EC-106: perna de movimentação entre contas do próprio titular
        // (pagamento de fatura, dos dois lados). A lista continua mostrando o
        // lançamento com o sinal real — este campo diz ao app para NÃO
        // apresentá-lo como receita/despesa do mês, que é como ele entra nos
        // totais. Campo somado ao contrato, nenhum removido.
        boolean internalTransfer,
        // V26: a linha entrou duas vezes (pela conexão e por um arquivo) ou o
        // usuário a descartou. Sai de toda soma e continua no extrato com selo.
        // Sem este campo o app marcava a linha e não tinha como desenhar a marca
        boolean ignored,
        // V28: dinheiro que ficou dentro da casa (Pix entre o casal, mesada).
        // Sai SÓ da soma da Casa — na análise pessoal do dono da linha o dinheiro
        // entrou mesmo, e escondê-lo dele seria mentir sobre o extrato
        boolean familyTransfer,
        // V29: uma das duas pernas de um estorno — a compra que saiu e o crédito
        // que voltou. As duas existem no extrato e o saldo fecha com elas; o que
        // estaria errado é somá-las. Sem este campo o app não consegue desenhar
        // "estornado" nem explicar por que a linha não entra no total
        boolean refunded,
        // No lado do CRÉDITO, qual compra ele estornou. Nulo no lado da compra.
        // É o que permite a tela dizer "estorno de <compra>" em vez de só
        // "estorno"
        UUID refundOfId,
        /*
         * EC-195: POR ONDE esta linha entrou e QUANDO. As duas perguntas que o
         * usuário faz quando desconfia de um número, e que até aqui nenhuma
         * resposta da API respondia.
         *
         * `source` é derivado, não gravado: quem tem conta veio de conexão, quem
         * tem arquivo veio de arquivo, quem não tem nenhum dos dois é histórico
         * anterior ao EC-113. Derivar mantém tudo consistente com o que já está
         * gravado e não exige decidir o que fazer com o passado.
         *
         * `importedAt` é a hora em que a linha entrou no NOSSO banco, e é outra
         * coisa que `date`: a compra foi no dia 3, o arquivo entrou no dia 20.
         * Quando o número da tela não bate com o do banco, a diferença entre
         * essas duas datas é quase sempre a explicação.
         */
        BankTransaction.ImportSource source,
        OffsetDateTime importedAt
) {
    public static BankTransactionResponse from(BankTransaction tx) {
        return new BankTransactionResponse(
                tx.getId(),
                tx.getTransactionId(),
                tx.getType(),
                tx.getAmount(),
                tx.displayDescription(),
                tx.getDescription(),
                tx.getDisplayAlias(),
                tx.getDate(),
                tx.getCategoryId(),
                tx.getReviewStatus(),
                tx.getCategorizedBy(),
                tx.getConfidence(),
                // chave normalizada do MOTOR: derivada do descritivo do banco,
                // nunca do apelido
                tx.getNormalizedDescription(),
                tx.getUploadId(),
                tx.getAccountId(),
                tx.isInternalTransfer(),
                tx.isIgnored(),
                tx.isFamilyTransfer(),
                tx.isRefunded(),
                tx.getRefundOfId(),
                tx.importSource(),
                tx.getCreatedAt());
    }
}
