package br.com.economize.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FinancialClockConfig — a zona do hoje financeiro")
class FinancialClockConfigTest {

    private final FinancialClockConfig config = new FinancialClockConfig();

    @Test
    @DisplayName("O relógio sai na zona configurada")
    void usaAZonaConfigurada() {
        assertThat(config.financialClock("America/Sao_Paulo").getZone())
                .isEqualTo(ZoneId.of("America/Sao_Paulo"));
        assertThat(config.financialClock("America/Manaus").getZone())
                .isEqualTo(ZoneId.of("America/Manaus"));
    }

    @Test
    @DisplayName("Zona inválida derruba a subida em vez de cair em UTC calada")
    void zonaInvalidaFalha() {
        assertThatThrownBy(() -> config.financialClock("Brasil/Lugar_Nenhum"))
                .isInstanceOf(java.time.DateTimeException.class);
    }
}
