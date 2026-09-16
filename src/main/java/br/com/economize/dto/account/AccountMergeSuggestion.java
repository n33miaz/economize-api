package br.com.economize.dto.account;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Duas origens que parecem ser a MESMA conta do mundo real.
 *
 * <p><b>O defeito que trouxe isto, medido na conta do dono em 16/09/2026.</b>
 * Ele relatou que "os números parecem estar meio embaralhados". Estavam, e por
 * um motivo concreto: a conta do Inter existia <b>duas vezes</b>. Uma origem
 * solta, criada pelos arquivos que ele importou, com <b>1.632 dos 1.967
 * lançamentos</b> dele e nenhum saldo; e uma origem ligada, trazida pelo
 * conector, com 75 lançamentos e o saldo de R$ 250,00. O mesmo acontecia com o
 * Mercado Pago (três origens) e com o Nubank.
 *
 * <p>Qualquer tela que agrupe por origem mostrava o mesmo banco duas vezes,
 * com números diferentes. E o saldo só era conhecido na origem que quase não
 * tinha história.
 *
 * <p><b>Por que sugerir e não juntar sozinho.</b> A adoção automática
 * ({@code ConnectorAccountService.adoptable}) exige nome E instituição iguais,
 * e é estreita de propósito — adotar a conta errada mistura o histórico de dois
 * cartões, que é pior do que a duplicata. Aqui os nomes não batiam nunca:
 * "Inter ····2750" contra "BANCO INTER ····2750", instituição "Inter" contra
 * "MeuPluggy". Afrouxar a regra automática trocaria um erro visível por um
 * silencioso. Quem sabe se são a mesma conta é o dono dela — então o app
 * aponta o par e pergunta.
 *
 * @param digits         os últimos dígitos que as duas carregam no rótulo; é o
 *                       sinal que levantou a suspeita, e vai na resposta para a
 *                       tela poder explicar POR QUE está perguntando
 * @param sourceId       a origem solta, que será absorvida e deixará de existir
 * @param targetId       a origem que fica — a ligada, porque é ela que continua
 *                       recebendo sincronização e saldo
 */
public record AccountMergeSuggestion(
        @Schema(description = "Últimos dígitos em comum nos dois rótulos", example = "2750")
        String digits,

        UUID sourceId,
        String sourceName,
        String sourceInstitution,
        @Schema(description = "Quantos lançamentos vão se mudar de origem")
        long sourceTransactions,

        UUID targetId,
        String targetName,
        String targetInstitution,
        long targetTransactions
) {
}
