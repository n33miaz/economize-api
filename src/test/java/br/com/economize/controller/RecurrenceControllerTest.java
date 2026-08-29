package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.analytics.AnalysisWindow;
import br.com.economize.dto.recurrence.CreateRecurringSeriesRequest;
import br.com.economize.dto.recurrence.ForecastItemResponse;
import br.com.economize.dto.recurrence.ForecastMonthResponse;
import br.com.economize.dto.recurrence.ForecastResponse;
import br.com.economize.dto.recurrence.RecurringSeriesResponse;
import br.com.economize.dto.recurrence.UpdateRecurringSeriesRequest;
import br.com.economize.exception.ResourceConflictException;
import br.com.economize.model.RecurringSeries;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.recurrence.RecurrenceDetectionService;
import br.com.economize.service.recurrence.RecurrenceForecastService;
import br.com.economize.service.recurrence.RecurringSeriesService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest(RecurrenceController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class})
class RecurrenceControllerTest {

    private static final String EMAIL = "teste@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private RecurrenceDetectionService detectionService;

    @MockitoBean
    private RecurringSeriesService seriesService;

    @MockitoBean
    private RecurrenceForecastService forecastService;

    @Test
    @DisplayName("POST /detect - Deve rodar a detecção do usuário autenticado e devolver o resumo")
    void detectShouldReturnSummary() {
        when(detectionService.detect(EMAIL))
                .thenReturn(new RecurrenceDetectionService.DetectionSummary(3, 1, 14));

        webTestClient.post()
                .uri("/api/v1/recurrences/detect")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.seriesCreated").isEqualTo(3)
                .jsonPath("$.seriesUpdated").isEqualTo(1)
                .jsonPath("$.linksCreated").isEqualTo(14);

        verify(detectionService).detect(EMAIL);
    }

    @Test
    @DisplayName("POST /detect - Sem token deve retornar 401")
    void detectShouldRequireAuthentication() {
        webTestClient.post()
                .uri("/api/v1/recurrences/detect")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(detectionService);
    }

    @Test
    @DisplayName("GET - Deve listar séries repassando o filtro de fluxo")
    void listShouldPassFlowFilter() {
        when(seriesService.list(EMAIL, "INTERNAL", null)).thenReturn(List.of(sampleResponse()));

        webTestClient.get()
                .uri("/api/v1/recurrences?flow=INTERNAL")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].merchantKey").isEqualTo("melodia")
                .jsonPath("$[0].nextDueDate").isEqualTo("2025-07-01");

        verify(seriesService).list(EMAIL, "INTERNAL", null);
    }

    @Test
    @DisplayName("GET - ?active=false deve repassar o filtro de inativas ao service")
    void listShouldPassActiveFilter() {
        when(seriesService.list(EMAIL, null, false)).thenReturn(List.of());

        webTestClient.get()
                .uri("/api/v1/recurrences?active=false")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk();

        verify(seriesService).list(EMAIL, null, false);
    }

    @Test
    @DisplayName("GET - Fluxo inválido deve retornar 400")
    void listShouldRejectInvalidFlow() {
        when(seriesService.list(EMAIL, "WEEKLY", null))
                .thenThrow(new IllegalArgumentException("Fluxo inválido: use EXPENSE, INCOME ou INTERNAL"));

        webTestClient.get()
                .uri("/api/v1/recurrences?flow=WEEKLY")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Fluxo inválido: use EXPENSE, INCOME ou INTERNAL");
    }

    @Test
    @DisplayName("POST - Deve agendar série manual e responder 201")
    void createShouldReturnCreated() {
        RecurringSeriesResponse scheduled = new RecurringSeriesResponse(
                UUID.randomUUID(), "aluguel", "Aluguel", null,
                RecurringSeries.Flow.EXPENSE, RecurringSeries.Cadence.MONTHLY,
                5, null, RecurringSeries.AmountType.FIXED, new BigDecimal("1500.00"),
                0, null, null, true, false, RecurringSeries.Source.USER,
                LocalDate.of(2026, 8, 15), null, LocalDate.of(2026, 9, 5));
        when(seriesService.create(eq(EMAIL), any(CreateRecurringSeriesRequest.class)))
                .thenReturn(scheduled);

        webTestClient.post()
                .uri("/api/v1/recurrences")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateRecurringSeriesRequest("Aluguel", "EXPENSE", "MONTHLY",
                        5, new BigDecimal("1500.00"), null, null, null, null, null))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.merchantKey").isEqualTo("aluguel")
                .jsonPath("$.source").isEqualTo("USER")
                .jsonPath("$.startsAt").isEqualTo("2026-08-15")
                .jsonPath("$.nextDueDate").isEqualTo("2026-09-05");

        verify(seriesService).create(eq(EMAIL), any(CreateRecurringSeriesRequest.class));
    }

    @Test
    @DisplayName("POST - Colisão de chave deve responder 409 ProblemDetail com o nome da série existente")
    void createShouldReturnConflictWhenSeriesAlreadyExists() {
        when(seriesService.create(eq(EMAIL), any(CreateRecurringSeriesRequest.class)))
                .thenThrow(new ResourceConflictException(
                        "Já existe uma série recorrente para esta cobrança: \"Aluguel Centro\" (chave \"aluguel\"). "
                                + "Edite-a ou reative-a em vez de criar outra."));

        webTestClient.post()
                .uri("/api/v1/recurrences")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateRecurringSeriesRequest("Aluguel", "EXPENSE", "MONTHLY",
                        5, new BigDecimal("1500.00"), null, null, null, null, null))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Conflito")
                .jsonPath("$.detail").value(detail ->
                        org.assertj.core.api.Assertions.assertThat((String) detail)
                                .contains("Aluguel Centro"));
    }

    @Test
    @DisplayName("POST - 409 deve trazer o seriesId da série existente no ProblemDetail")
    void createConflictShouldExposeTheExistingSeriesId() {
        UUID existingId = UUID.randomUUID();
        when(seriesService.create(eq(EMAIL), any(CreateRecurringSeriesRequest.class)))
                .thenThrow(new ResourceConflictException(
                        "Já existe uma série recorrente para esta cobrança.",
                        Map.of("seriesId", existingId)));

        // sem o id no corpo, o app só consegue repetir a mensagem: com ele,
        // oferece "editar/reativar" apontando para a série certa
        webTestClient.post()
                .uri("/api/v1/recurrences")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateRecurringSeriesRequest("Aluguel", "EXPENSE", "MONTHLY",
                        5, new BigDecimal("1500.00"), null, null, null, null, null))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.seriesId").isEqualTo(existingId.toString());
    }

    @Test
    @DisplayName("PATCH /{id} - Deve repassar vigência e ritmo ao service")
    void updateShouldPassValidityAndRhythm() {
        UUID id = UUID.randomUUID();
        when(seriesService.update(eq(EMAIL), eq(id), any(UpdateRecurringSeriesRequest.class)))
                .thenReturn(sampleResponse());

        webTestClient.patch()
                .uri("/api/v1/recurrences/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateRecurringSeriesRequest(null, null, null, null, null,
                        "MONTHLY", 20, LocalDate.of(2026, 9, 1), LocalDate.of(2027, 6, 30)))
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<UpdateRecurringSeriesRequest> captor =
                ArgumentCaptor.forClass(UpdateRecurringSeriesRequest.class);
        verify(seriesService).update(eq(EMAIL), eq(id), captor.capture());
        assertThat(captor.getValue().cadence()).isEqualTo("MONTHLY");
        assertThat(captor.getValue().anchorDay()).isEqualTo(20);
        assertThat(captor.getValue().startsAt()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(captor.getValue().endsAt()).isEqualTo(LocalDate.of(2027, 6, 30));
    }

    @Test
    @DisplayName("PATCH /{id} - anchorDay fora de 1..31 deve retornar 400 sem chamar o service")
    void updateShouldRejectAnchorDayOutOfRange() {
        webTestClient.patch()
                .uri("/api/v1/recurrences/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateRecurringSeriesRequest(null, null, null, null, null,
                        null, 0, null, null))
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(seriesService);
    }

    @Test
    @DisplayName("POST - Payload sem displayName/expectedAmount deve retornar 400 sem chamar o service")
    void createShouldRejectInvalidPayload() {
        webTestClient.post()
                .uri("/api/v1/recurrences")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateRecurringSeriesRequest(" ", "EXPENSE", "MONTHLY",
                        5, null, null, null, null, null, null))
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(seriesService);
    }

    @Test
    @DisplayName("POST - anchorDay fora de 1..31 deve retornar 400 sem chamar o service")
    void createShouldRejectAnchorDayOutOfRange() {
        webTestClient.post()
                .uri("/api/v1/recurrences")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateRecurringSeriesRequest("Aluguel", "EXPENSE", "MONTHLY",
                        32, new BigDecimal("1500.00"), null, null, null, null, null))
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(seriesService);
    }

    @Test
    @DisplayName("GET /forecast - O contrato do APK publicado (months + startingBalance) segue intacto")
    void forecastShouldPassParameters() {
        // retrocompatibilidade: sem recorte na query o service recebe janela
        // nula, que é o "mês do calendário a partir de hoje" de sempre
        ForecastResponse response = new ForecastResponse(new BigDecimal("1000.00"), 1, List.of(
                new ForecastMonthResponse("2026-08", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                        new BigDecimal("4500.00"),
                        new BigDecimal("1500.00"), new BigDecimal("3000.00"),
                        new BigDecimal("4000.00"), List.of(
                        new ForecastItemResponse(UUID.randomUUID(), "Aluguel",
                                RecurringSeries.Flow.EXPENSE, 5, LocalDate.of(2026, 8, 5),
                                new BigDecimal("1500.00"),
                                RecurringSeries.Source.USER, false)))));
        when(forecastService.forecast(EMAIL, 6, new BigDecimal("1000.00"), null)).thenReturn(response);

        webTestClient.get()
                .uri("/api/v1/recurrences/forecast?months=6&startingBalance=1000.00")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.startingBalance").isEqualTo(1000.00)
                .jsonPath("$.anchorDay").isEqualTo(1)
                .jsonPath("$.months[0].month").isEqualTo("2026-08")
                .jsonPath("$.months[0].start").isEqualTo("2026-08-01")
                .jsonPath("$.months[0].end").isEqualTo("2026-08-31")
                .jsonPath("$.months[0].cumulativeNet").isEqualTo(4000.00)
                .jsonPath("$.months[0].items[0].dueDate").isEqualTo("2026-08-05")
                .jsonPath("$.months[0].items[0].settled").isEqualTo(false);

        verify(forecastService).forecast(EMAIL, 6, new BigDecimal("1000.00"), null);
    }

    @Test
    @DisplayName("GET /forecast - start/end viram o primeiro período da projeção")
    void forecastShouldAcceptTheAnchoredWindow() {
        when(forecastService.forecast(eq(EMAIL), eq(3), any(), any(AnalysisWindow.class)))
                .thenReturn(new ForecastResponse(null, 12, List.of()));

        webTestClient.get()
                .uri("/api/v1/recurrences/forecast?months=3&start=2026-07-12&end=2026-08-11")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.anchorDay").isEqualTo(12);

        AnalysisWindow window = capturedForecastWindow();
        assertThat(window.monthLabel()).isNull();
        assertThat(window.start()).isEqualTo(LocalDate.of(2026, 7, 12));
        assertThat(window.end()).isEqualTo(LocalDate.of(2026, 8, 11));
    }

    @Test
    @DisplayName("GET /forecast - month=YYYY-MM é o mesmo mês do calendário de sempre")
    void forecastShouldAcceptTheCalendarMonth() {
        when(forecastService.forecast(eq(EMAIL), any(), any(), any(AnalysisWindow.class)))
                .thenReturn(new ForecastResponse(null, 1, List.of()));

        webTestClient.get()
                .uri("/api/v1/recurrences/forecast?month=2026-07")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk();

        AnalysisWindow window = capturedForecastWindow();
        assertThat(window.monthLabel()).isEqualTo("2026-07");
        assertThat(window.start()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(window.end()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    @DisplayName("GET /forecast - month junto com janela responde 400 ProblemDetail")
    void forecastShouldRejectMonthAndWindowTogether() {
        webTestClient.get()
                .uri("/api/v1/recurrences/forecast?month=2026-07&start=2026-07-12&end=2026-08-11")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Requisição Inválida")
                .jsonPath("$.detail").value(detail ->
                        assertThat((String) detail).contains("nunca os dois"));

        verifyNoInteractions(forecastService);
    }

    @Test
    @DisplayName("GET /forecast - Janela incompleta, invertida, acima do teto ou malformada responde 400")
    void forecastShouldRejectBrokenWindows() {
        webTestClient.get()
                .uri("/api/v1/recurrences/forecast?start=2026-07-12")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(detail ->
                        assertThat((String) detail).contains("informados juntos"));

        webTestClient.get()
                .uri("/api/v1/recurrences/forecast?start=2026-08-12&end=2026-07-12")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("end não pode ser anterior a start");

        webTestClient.get()
                .uri("/api/v1/recurrences/forecast?start=2025-01-01&end=2026-08-12")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(detail ->
                        assertThat((String) detail).contains("Janela máxima de 366 dias"));

        webTestClient.get()
                .uri("/api/v1/recurrences/forecast?start=12/07/2026&end=2026-08-11")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("start inválido — use o formato YYYY-MM-DD");

        verifyNoInteractions(forecastService);
    }

    @Test
    @DisplayName("GET /forecast - Sem parâmetros deve delegar com defaults nulos")
    void forecastShouldUseDefaults() {
        when(forecastService.forecast(EMAIL, null, null, null))
                .thenReturn(new ForecastResponse(null, 1, List.of()));

        webTestClient.get()
                .uri("/api/v1/recurrences/forecast")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk();

        verify(forecastService).forecast(EMAIL, null, null, null);
    }

    @Test
    @DisplayName("GET /forecast - months fora do intervalo responde 400 ProblemDetail")
    void forecastShouldRejectMonthsOutOfRange() {
        when(forecastService.forecast(EMAIL, 13, null, null))
                .thenThrow(new IllegalArgumentException("months deve estar entre 1 e 12"));

        webTestClient.get()
                .uri("/api/v1/recurrences/forecast?months=13")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("months deve estar entre 1 e 12");
    }

    private AnalysisWindow capturedForecastWindow() {
        ArgumentCaptor<AnalysisWindow> captor = ArgumentCaptor.forClass(AnalysisWindow.class);
        verify(forecastService).forecast(eq(EMAIL), any(), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("PATCH /{id} - Deve aplicar atualização parcial")
    void updateShouldApplyPartialChanges() {
        UUID id = UUID.randomUUID();
        when(seriesService.update(eq(EMAIL), eq(id), any(UpdateRecurringSeriesRequest.class)))
                .thenReturn(sampleResponse());

        webTestClient.patch()
                .uri("/api/v1/recurrences/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateRecurringSeriesRequest("Streaming Melodia", null, null, null, null,
                        null, null, null, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.merchantKey").isEqualTo("melodia");

        verify(seriesService).update(eq(EMAIL), eq(id), any(UpdateRecurringSeriesRequest.class));
    }

    @Test
    @DisplayName("PATCH /{id} - Valor esperado negativo deve retornar 400 sem chamar o service")
    void updateShouldRejectNegativeExpectedAmount() {
        webTestClient.patch()
                .uri("/api/v1/recurrences/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateRecurringSeriesRequest(null, null, null, null, new BigDecimal("-10.00"),
                        null, null, null, null))
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(seriesService);
    }

    @Test
    @DisplayName("DELETE /{id} - Série detectada é desativada, não apagada")
    void deleteShouldReportDeactivationForDetectedSeries() {
        UUID id = UUID.randomUUID();
        when(seriesService.delete(EMAIL, id)).thenReturn(false);

        webTestClient.delete()
                .uri("/api/v1/recurrences/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.deleted").isEqualTo(false)
                .jsonPath("$.deactivated").isEqualTo(true);

        verify(seriesService).delete(EMAIL, id);
    }

    private RecurringSeriesResponse sampleResponse() {
        return new RecurringSeriesResponse(
                UUID.randomUUID(), "melodia", "Streaming Melodia", null,
                RecurringSeries.Flow.EXPENSE, RecurringSeries.Cadence.MONTHLY,
                1, 2, RecurringSeries.AmountType.FIXED, new BigDecimal("21.90"),
                4, OffsetDateTime.parse("2025-01-31T12:00:00Z"),
                OffsetDateTime.parse("2025-06-02T12:00:00Z"),
                true, false, RecurringSeries.Source.DETECTED, null, null, LocalDate.of(2025, 7, 1));
    }

    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }
}
