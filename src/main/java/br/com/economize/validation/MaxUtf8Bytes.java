package br.com.economize.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Teto em BYTES UTF-8, não em caracteres.
 *
 * <p>Existe por causa do BCrypt: o Spring Security 6.5 recusa senha com mais
 * de 72 bytes lançando {@code IllegalArgumentException} no {@code encode}. O
 * {@code @Size} conta caracteres, e uma senha de 30 emojis (120 bytes) passava
 * por ele e só estourava lá dentro — no cadastro virava 409 como se o e-mail
 * já existisse, e na troca/recuperação um 400 com texto em inglês do Spring.
 * Validar na entrada devolve 400 com mensagem que o app consegue mostrar.
 *
 * <p>{@code null} é válido: obrigatoriedade é assunto do {@code @NotBlank}.
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {

    /**
     * Mensagem única das três telas de senha (cadastro, troca, recuperação):
     * "bytes" não diz nada a quem digita, então o texto fala de acentos e emojis.
     */
    String SENHA = "A senha é longa demais: acentos e emojis ocupam mais espaço. "
            + "Use até 72 letras e números simples";

    /** Quantidade máxima de bytes depois de codificado em UTF-8. */
    int value();

    String message() default "Texto longo demais: no máximo {value} bytes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
