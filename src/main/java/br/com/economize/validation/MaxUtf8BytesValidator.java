package br.com.economize.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

/** Conta os bytes UTF-8 do texto e compara com o teto de {@link MaxUtf8Bytes}. */
public class MaxUtf8BytesValidator implements ConstraintValidator<MaxUtf8Bytes, CharSequence> {

    private int max;

    @Override
    public void initialize(MaxUtf8Bytes annotation) {
        this.max = annotation.value();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return value.toString().getBytes(StandardCharsets.UTF_8).length <= max;
    }
}
