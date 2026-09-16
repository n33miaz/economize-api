package br.com.economize.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * As credenciais do login.
 *
 * <p>Os dois campos de aparelho sao OPCIONAIS e nao existiam no APK publicado:
 * ausentes, o comportamento e exatamente o de antes.
 */
@Data
public class AuthRequest {

    @NotBlank(message = "Informe seu e-mail")
    private String email;

    /**
     * Sem piso de tamanho aqui, e é de propósito: o piso de 8 caracteres vale
     * para CRIAR e TROCAR senha. Cobrá-lo no login trancaria para fora quem
     * criou a conta antes da regra existir — punindo o usuário por um defeito
     * nosso em vez de corrigi-lo.
     */
    @NotBlank(message = "Informe sua senha")
    private String password;

    /**
     * Segredo de aparelho conhecido, emitido num segundo passo anterior. Se
     * conferir, o segundo fator nao e pedido — e o que torna o fator
     * suportavel no celular de todo dia.
     */
    @Schema(description = "Segredo do aparelho, se ele ja foi lembrado")
    private String deviceToken;

    /** "iPhone de Alice". So rotulo, para a pessoa reconhecer a lista. */
    @Schema(description = "Como este aparelho aparece na lista de conhecidos")
    private String deviceLabel;
}
