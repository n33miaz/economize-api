package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.analytics.MonthlyAnalyticsResponse;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.AnalyticsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest(AnalyticsController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class})
class AnalyticsControllerTest {

    private static final String EMAIL = "teste@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private AnalyticsService analyticsService;

    @Test
    @DisplayName("GET /monthly - month continua funcionando igual (retrocompatibilidade)")
    void monthlyKeepsTheMonthContract() {
        when(analyticsService.analyze(eq(EMAIL), any(AnalysisWindow.class)))
                .thenReturn(monthResponse());

        webTestClient.get()
                .uri("/api/v1/analytics/monthly?month=2026-07")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.month").isEqualTo("2026-07")
                .jsonPath("$.start").isEqualTo("2026-07-01")
                .jsonPath("$.previous.month").isEqualTo("2026-06");

        assertThat(capturedWindow().monthLabel()).isEqualTo("2026-07");
    }

    @Test
    @DisplayName("GET /monthly - Sem parâmetro nenhum usa o mês corrente")
    void monthlyDefaultsToCurrentMonth() {
        when(analyticsService.analyze(eq(EMAIL), any(AnalysisWindow.class)))
                .thenReturn(monthResponse());

        webTestClient.get()
                .uri("/api/v1/analytics/monthly")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk();

        assertThat(capturedWindow().monthLabel())
                .isEqualTo(YearMonth.now(ZoneOffset.UTC).toString());
    }

    @Test
    @DisplayName("GET /monthly - start/end viram janela ancorada com comparável do mesmo tamanho")
    void monthlyAcceptsAnchoredWindow() {
        when(analyticsService.analyze(eq(EMAIL), any(AnalysisWindow.class)))
                .thenReturn(windowResponse());

        webTestClient.get()
                .uri("/api/v1/analytics/monthly?start=2026-07-12&end=2026-08-12")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                // janela ancorada não pertence a mês do calendário
                .jsonPath("$.month").isEmpty()
                .jsonPath("$.start").isEqualTo("2026-07-12")
                .jsonPath("$.end").isEqualTo("2026-08-12")
                .jsonPath("$.previous.start").isEqualTo("2026-06-10")
                .jsonPath("$.previous.end").isEqualTo("2026-07-11");

        AnalysisWindow window = capturedWindow();
        assertThat(window.monthLabel()).isNull();
        assertThat(window.start()).isEqualTo(LocalDate.of(2026, 7, 12));
        assertThat(window.end()).isEqualTo(LocalDate.of(2026, 8, 12));
    }

    @Test
    @DisplayName("GET /monthly - month junto com janela responde 400 ProblemDetail")
    void monthlyRejectsMonthAndWindowTogether() {
        webTestClient.get()
                .uri("/api/v1/analytics/monthly?month=2026-07&start=2026-07-12&end=2026-08-12")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Requisição Inválida")
                .jsonPath("$.detail").value(detail ->
                        assertThat((String) detail).contains("nunca os dois"));

        verifyNoInteractions(analyticsService);
    }

    @Test
    @DisplayName("GET /monthly - Janela incompleta ou invertida responde 400")
    void monthlyRejectsBrokenWindows() {
        webTestClient.get()
                .uri("/api/v1/analytics/monthly?start=2026-07-12")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(detail ->
                        assertThat((String) detail).contains("informados juntos"));

        webTestClient.get()
                .uri("/api/v1/analytics/monthly?start=2026-08-12&end=2026-07-12")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("end não pode ser anterior a start");

        verifyNoInteractions(analyticsService);
    }

    @Test
    @DisplayName("GET /monthly - Janela acima do teto responde 400 dizendo o tamanho pedido")
    void monthlyRejectsWindowAboveTheCap() {
        webTestClient.get()
                .uri("/api/v1/analytics/monthly?start=2025-01-01&end=2026-08-12")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(detail ->
                        assertThat((String) detail).contains("Janela máxima de 366 dias"));

        verifyNoInteractions(analyticsService);
    }

    @Test
    @DisplayName("GET /monthly - Data malformada é 400 de validação, nunca 500")
    void monthlyRejectsMalformedDates() {
        webTestClient.get()
                .uri("/api/v1/analytics/monthly?start=12/07/2026&end=2026-08-12")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("start inválido — use o formato YYYY-MM-DD");

        verifyNoInteractions(analyticsService);
    }

    @Test
    @DisplayName("GET /monthly - Sem token responde 401")
    void monthlyRequiresAuthentication() {
        webTestClient.get()
                .uri("/api/v1/analytics/monthly")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(analyticsService);
    }

    private AnalysisWindow capturedWindow() {
        ArgumentCaptor<AnalysisWindow> captor = ArgumentCaptor.forClass(AnalysisWindow.class);
        verify(analyticsService).analyze(eq(EMAIL), captor.capture());
        return captor.getValue();
    }

    private MonthlyAnalyticsResponse monthResponse() {
        return new MonthlyAnalyticsResponse(
                "2026-07", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                new BigDecimal("5000.00"), new BigDecimal("3200.00"), new BigDecimal("1800.00"),
                new MonthlyAnalyticsResponse.MonthTotals("2026-06",
                        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                        new BigDecimal("5000.00"), new BigDecimal("3000.00"), new BigDecimal("2000.00")),
                List.of(), 0);
    }

    private MonthlyAnalyticsResponse windowResponse() {
        return new MonthlyAnalyticsResponse(
                null, LocalDate.of(2026, 7, 12), LocalDate.of(2026, 8, 12),
                new BigDecimal("5000.00"), new BigDecimal("3200.00"), new BigDecimal("1800.00"),
                new MonthlyAnalyticsResponse.MonthTotals(null,
                        LocalDate.of(2026, 6, 10), LocalDate.of(2026, 7, 11),
                        new BigDecimal("5000.00"), new BigDecimal("3000.00"), new BigDecimal("2000.00")),
                List.of(), 0);
    }

    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }
}
