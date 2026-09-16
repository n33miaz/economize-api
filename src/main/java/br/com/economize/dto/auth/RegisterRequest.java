package br.com.economize.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * O cadastro.
 *
 * <p><b>O buraco que isto fecha, com data.</b> Em 15/09/2026, medido contra a
 * API rodando: {@code POST /auth/register} com
 * {@code {"name":"","email":"nao-e-email","password":""}} respondia <b>200</b> e
 * criava a conta — e o {@code POST /auth/login} seguinte, com a mesma senha
 * vazia, devolvia <b>200 com token válido</b>. Não era descuido de uma regra:
 * este era o único DTO do projeto <b>sem restrição nenhuma</b>, e o controller
 * era o único ponto <b>sem {@code @Valid}</b> no corpo. Sem a anotação no
 * controller, restrição aqui não roda — as duas metades precisam existir.
 *
 * <p>A incoerência ficava gritante ao lado das telas de senha: trocar a senha
 * exigia 8 caracteres ({@code ChangePasswordRequest}), recuperar exigia 8
 * ({@code ResetPasswordRequest}), e CRIAR não exigia nada. O piso aqui é o
 * mesmo dos outros dois de propósito — três regras diferentes para a mesma
 * senha é como um app acaba com contas que não conseguem trocar a própria
 * senha.
 */
@Data
public class RegisterRequest {

    @Schema(description = "Como a pessoa quer ser chamada no app", example = "Alice")
    @NotBlank(message = "Diga seu nome")
    @Size(max = 120, message = "O nome não pode passar de 120 caracteres")
    private String name;

    @Schema(example = "alice@exemplo.com")
    @NotBlank(message = "Informe seu e-mail")
    @Email(message = "E-mail inválido")
    @Size(max = 180, message = "O e-mail não pode passar de 180 caracteres")
    private String email;

    @Schema(description = "Mínimo de 8 caracteres — o mesmo piso da troca e da recuperação de senha")
    @NotBlank(message = "Escolha uma senha")
    @Size(min = 8, max = 100, message = "A senha precisa ter de 8 a 100 caracteres")
    private String password;
}
