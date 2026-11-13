package br.com.economize.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Token x conta: versão da sessão e emissão depois da criação")
class SessionStampTest {

    private static final Instant CRIACAO = Instant.parse("2026-11-13T10:00:00.750Z");
    private final SessionVersionService.SessionStamp conta =
            new SessionVersionService.SessionStamp(0, CRIACAO.getEpochSecond());

    @Test
    @DisplayName("emitido no mesmo segundo da criação (iat sem fração) vale")
    void sameSecondIsAccepted() {
        assertThat(conta.accepts(0, Date.from(Instant.parse("2026-11-13T10:00:00Z")))).isTrue();
    }

    @Test
    @DisplayName("emitido depois vale; antes da criação, não; versão diferente, não")
    void beforeCreationOrOtherVersionIsRefused() {
        assertThat(conta.accepts(0, Date.from(CRIACAO.plusSeconds(3600)))).isTrue();
        assertThat(conta.accepts(0, Date.from(CRIACAO.minusSeconds(1)))).isFalse();
        assertThat(conta.accepts(1, Date.from(CRIACAO.plusSeconds(1)))).isFalse();
        assertThat(conta.accepts(0, null)).isFalse();
    }
}
