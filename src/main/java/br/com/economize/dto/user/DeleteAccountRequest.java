package br.com.economize.dto.user;

import jakarta.validation.constraints.NotBlank;

/**
 * Excluir a conta exige a SENHA — e, com o segundo fator ligado, também o
 * código do autenticador (ou um código de recuperação). A sessão aberta sozinha
 * não basta: quem pega o celular desbloqueado não pode apagar a conta de outra
 * pessoa.
 */
public record DeleteAccountRequest(
        @NotBlank(message = "Informe sua senha para excluir a conta")
        String password,
        String code) {
}
