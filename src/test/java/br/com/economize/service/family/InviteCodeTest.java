package br.com.economize.service.family;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InviteCode (EC-149)")
class InviteCodeTest {

    @Test
    @DisplayName("Gera 8 caracteres, todos do alfabeto sem I/1 e O/0")
    void geraDoAlfabeto() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 200; i++) {
            String code = InviteCode.generate(random);
            assertThat(code).hasSize(8);
            assertThat(code.chars().allMatch(c -> InviteCode.ALPHABET.indexOf(c) >= 0)).isTrue();
            assertThat(code).doesNotContainPattern("[IO01]");
        }
    }

    @Test
    @DisplayName("Normaliza o que a pessoa digitou: maiúsculas, sem espaços nem hífens")
    void normaliza() {
        assertThat(InviteCode.normalize(" abcd-2345 ")).isEqualTo("ABCD2345");
        assertThat(InviteCode.normalize("AB CD 23 45")).isEqualTo("ABCD2345");
        assertThat(InviteCode.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("O hash é SHA-256 em hex, determinístico e diferente do código")
    void hashDeterministico() {
        String hash = InviteCode.hash("ABCD2345");

        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
        assertThat(hash).isEqualTo(InviteCode.hash("ABCD2345"));
        assertThat(hash).isNotEqualTo(InviteCode.hash("ABCD2346"));
    }
}
