package br.com.economize.dto.auth;

import br.com.economize.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank(message = "Escolha a nova senha")
        @Size(min = 8, max = 100, message = "A senha precisa ter de 8 a 100 caracteres")
        @MaxUtf8Bytes(value = 72, message = MaxUtf8Bytes.SENHA)
        String newPassword
) {
}
