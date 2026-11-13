package br.com.economize.validation;

import br.com.economize.dto.auth.RegisterRequest;
import br.com.economize.dto.auth.ResetPasswordRequest;
import br.com.economize.dto.user.ChangePasswordRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O teto de 72 bytes nas três telas de senha. O BCrypt conta bytes UTF-8, e o
 * {@code @Size} conta caracteres: sem {@link MaxUtf8Bytes} uma senha acentuada
 * de 40 caracteres passava pela validação e estourava dentro do encoder.
 */
class PasswordMaxUtf8BytesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static <T> Set<String> mensagens(Set<ConstraintViolation<T>> violacoes) {
        return violacoes.stream().map(ConstraintViolation::getMessage)
                .collect(java.util.stream.Collectors.toSet());
    }

    private Set<String> troca(String senha) {
        return mensagens(validator.validate(new ChangePasswordRequest("atual-12345", senha)));
    }

    private Set<String> recuperacao(String senha) {
        return mensagens(validator.validate(new ResetPasswordRequest("token", senha)));
    }

    private Set<String> cadastro(String senha) {
        RegisterRequest request = new RegisterRequest();
        request.setName("Ana");
        request.setEmail("ana@economize.dev");
        request.setPassword(senha);
        return mensagens(validator.validate(request));
    }

    @Test
    @DisplayName("72 bytes passam; 73 são recusados com a mensagem em português — nas três telas")
    void limiteEmBytesNasTresTelas() {
        String ascii72 = "a".repeat(72);
        String ascii73 = "a".repeat(73);
        // 36 "é" = 72 bytes; 37 = 74 bytes, com só 37 caracteres
        String acento36 = "é".repeat(36);
        String acento37 = "é".repeat(37);

        for (var tela : java.util.List.<java.util.function.Function<String, Set<String>>>of(
                this::troca, this::recuperacao, this::cadastro)) {
            assertThat(tela.apply(ascii72)).isEmpty();
            assertThat(tela.apply(acento36)).isEmpty();
            assertThat(tela.apply(ascii73)).containsExactly(MaxUtf8Bytes.SENHA);
            assertThat(tela.apply(acento37)).containsExactly(MaxUtf8Bytes.SENHA);
        }
    }

    @Test
    @DisplayName("troca e recuperação deixaram o texto padrão em inglês")
    void mensagensEmPortugues() {
        assertThat(troca("curta")).containsExactly("A senha precisa ter de 8 a 100 caracteres");
        assertThat(recuperacao("")).contains("Escolha a nova senha");
    }
}
