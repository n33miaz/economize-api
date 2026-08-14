package br.com.economize.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialSanitizerTest {

    private final CredentialSanitizer sanitizer = new CredentialSanitizer();

    @Test
    void trimsTheTrailingSpaceThatPanelsAddSilently() {
        assertThat(CredentialSanitizer.sanitize("SYKc7TOsVkzIez18 ")).isEqualTo("SYKc7TOsVkzIez18");
        assertThat(CredentialSanitizer.sanitize(" postgres.abc\n")).isEqualTo("postgres.abc");
    }

    @Test
    void dropsQuotesThatCameAlongInTheCopy() {
        assertThat(CredentialSanitizer.sanitize("\"senha-secreta\"")).isEqualTo("senha-secreta");
        assertThat(CredentialSanitizer.sanitize("'senha-secreta'")).isEqualTo("senha-secreta");
    }

    @Test
    void leavesLegitimateValuesAlone() {
        // aspas no meio são conteúdo, não invólucro
        assertThat(CredentialSanitizer.sanitize("bcKK!*qi92$GAGuNuy^9BC$^Fe27009bb"))
                .isEqualTo("bcKK!*qi92$GAGuNuy^9BC$^Fe27009bb");
        assertThat(CredentialSanitizer.sanitize("se\"nha")).isEqualTo("se\"nha");
    }

    @Test
    void overridesTheDirtyValueForEveryoneWhoResolvesPlaceholders() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("teste",
                Map.of("DB_PASSWORD", "senha-com-espaco  ", "DB_USER", "postgres.abc")));

        sanitizer.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("DB_PASSWORD")).isEqualTo("senha-com-espaco");
        assertThat(environment.getProperty("DB_USER")).isEqualTo("postgres.abc");
    }
}
