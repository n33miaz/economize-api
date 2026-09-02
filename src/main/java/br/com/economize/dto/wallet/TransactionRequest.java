package br.com.economize.dto.wallet;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Compra ou venda de um ativo na carteira.
 *
 * <p>Não tinha restrição nenhuma: um corpo vazio chegava ao serviço e só
 * quebrava no banco, virando 500 no lugar de 400 — e um {@code type} qualquer
 * ("BANANA") era aceito, apesar de ser ele quem decide o sinal da posição.
 */
@Data
public class TransactionRequest {

    @Schema(description = "Código do ativo", example = "PETR4")
    @NotBlank(message = "O código do ativo é obrigatório")
    @Size(max = 20, message = "O código do ativo não passa de 20 caracteres")
    private String assetCode;

    @Schema(description = "BUY para compra, SELL para venda", example = "BUY")
    @NotBlank(message = "O tipo é obrigatório")
    @Pattern(regexp = "BUY|SELL", message = "O tipo deve ser BUY ou SELL")
    private String type;

    // Zero não é operação: nem compra nem venda de nada
    @Schema(description = "Quantidade de cotas", example = "100")
    @NotNull(message = "A quantidade é obrigatória")
    @DecimalMin(value = "0.00000001", message = "A quantidade deve ser maior que zero")
    private BigDecimal quantity;

    @Schema(description = "Preço unitário na data da operação", example = "38.42")
    @NotNull(message = "O preço é obrigatório")
    @DecimalMin(value = "0.00000001", message = "O preço deve ser maior que zero")
    private BigDecimal priceAtTransaction;
}
