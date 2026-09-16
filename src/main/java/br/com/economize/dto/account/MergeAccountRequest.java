package br.com.economize.dto.account;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Para qual origem o histórico vai.
 *
 * <p>A origem que DESAPARECE é a do caminho da requisição; esta é a que fica.
 * A ordem não é arbitrária: quem fica tem de ser a conta ligada ao conector,
 * porque é ela que continua recebendo sincronização e saldo. Apagar a ligada
 * faria o conector recriá-la na leitura seguinte, e a duplicata voltaria no dia
 * seguinte — motivo pelo qual o serviço recusa esse caso em vez de obedecer.
 */
public record MergeAccountRequest(
        @Schema(description = "A conta que FICA e absorve o histórico da outra")
        @NotNull(message = "Diga para qual conta o histórico vai")
        UUID intoAccountId) {
}
