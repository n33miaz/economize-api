package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.wish.WishRequests;
import br.com.economize.dto.wish.WishResponses;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.wish.CommittedIncomeService;
import br.com.economize.service.wish.IncomeSourceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * As rotas de renda e jornada (EC-135/136/141).
 *
 * <p>É por aqui que entram as duas coisas que o resto do sistema usa para
 * calcular tudo: a âncora de cada fonte (que decide de qual ciclo é o gasto) e
 * a jornada (que transforma preço em horas de trabalho). Nenhuma tinha teste
 * de rota.
 */
@WebFluxTest(IncomeController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class })
@DisplayName("IncomeController — renda, âncora e jornada")
class IncomeControllerTest {

    private static final String EMAIL = "teste@economize.app";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private IncomeSourceService incomeSourceService;

    @MockitoBean
    private CommittedIncomeService committedIncomeService;

    private String bearer() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }

    private WishResponses.IncomeSourceItem fonte(String nome, Short ancora) {
        return new WishResponses.IncomeSourceItem(UUID.randomUUID(), "MEAL_VOUCHER", nome,
                new BigDecimal("600.00"), ancora, true, true, null);
    }

    @Test
    @DisplayName("GET / devolve fontes, jornada e sugestões")
    void panoramaDeRenda() {
        when(incomeSourceService.overview(EMAIL)).thenReturn(new WishResponses.IncomeOverview(
                List.of(fonte("Vale-refeição", (short) 25)),
                new WishResponses.WorkProfileItem(5, new BigDecimal("8.00"), new BigDecimal("176.00")),
                List.of()));

        webTestClient.get().uri("/api/v1/income")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sources[0].name").isEqualTo("Vale-refeição")
                .jsonPath("$.sources[0].anchorDay").isEqualTo(25)
                .jsonPath("$.workProfile.hoursPerMonth").isEqualTo(176.00);
    }

    @Test
    @DisplayName("GET /committed responde o que o próximo salário já deve")
    void comprometido() {
        when(committedIncomeService.overview(EMAIL)).thenReturn(new WishResponses.CommittedOverview(
                true, LocalDate.of(2026, 9, 5), 3, new BigDecimal("5000.00"),
                BigDecimal.ZERO, List.of(), new BigDecimal("1800.00"), List.of(),
                new BigDecimal("3200.00")));

        webTestClient.get().uri("/api/v1/income/committed")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.salaryKnown").isEqualTo(true)
                .jsonPath("$.committedAfterSalary").isEqualTo(1800.00)
                .jsonPath("$.free").isEqualTo(3200.00);
    }

    @Test
    @DisplayName("POST /sources cadastra e responde 201")
    void cadastraFonte() {
        when(incomeSourceService.create(eq(EMAIL), any(WishRequests.CreateIncomeSource.class)))
                .thenReturn(fonte("Vale-refeição", (short) 25));

        webTestClient.post().uri("/api/v1/income/sources")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("kind", "MEAL_VOUCHER", "name", "Vale-refeição",
                        "expectedAmount", 600, "anchorDay", 25))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.anchorDay").isEqualTo(25);
    }

    @Test
    @DisplayName("Fonte sem tipo ou sem nome é 400")
    void fonteExigeTipoENome() {
        webTestClient.post().uri("/api/v1/income/sources")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Sem tipo"))
                .exchange()
                .expectStatus().isBadRequest();

        verify(incomeSourceService, never()).create(any(), any());
    }

    @Test
    @DisplayName("Âncora fora de 1 a 31 é recusada na borda")
    void ancoraForaDaFaixa() {
        for (int dia : new int[] { 0, 32 }) {
            webTestClient.post().uri("/api/v1/income/sources")
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("kind", "SALARY", "name", "Salário", "anchorDay", dia))
                    .exchange()
                    .expectStatus().isBadRequest();
        }

        // O banco tem o mesmo CHECK; a validação aqui existe para responder 400
        // em vez de estourar no INSERT
        verify(incomeSourceService, never()).create(any(), any());
    }

    @Test
    @DisplayName("PUT /work-profile aceita meia hora: 6h30 é 6.50")
    void jornadaAceitaMeiaHora() {
        when(incomeSourceService.saveWorkProfile(eq(EMAIL), any(WishRequests.SaveWorkProfile.class)))
                .thenReturn(new WishResponses.WorkProfileItem(6, new BigDecimal("6.50"),
                        new BigDecimal("169.00")));

        webTestClient.put().uri("/api/v1/income/work-profile")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("daysPerWeek", 6, "hoursPerDay", 6.5))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.hoursPerDay").isEqualTo(6.50);
    }

    @Test
    @DisplayName("Jornada impossível é recusada: 8 dias na semana, 25 horas no dia")
    void jornadaImpossivel() {
        for (Map<String, Object> corpo : List.of(
                Map.<String, Object>of("daysPerWeek", 8, "hoursPerDay", 8),
                Map.<String, Object>of("daysPerWeek", 5, "hoursPerDay", 25),
                Map.<String, Object>of("daysPerWeek", 5, "hoursPerDay", 0))) {
            webTestClient.put().uri("/api/v1/income/work-profile")
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(corpo)
                    .exchange()
                    .expectStatus().isBadRequest();
        }

        verify(incomeSourceService, never()).saveWorkProfile(any(), any());
    }

    @Test
    @DisplayName("DELETE /sources/{id} responde 204")
    void apagaFonte() {
        UUID id = UUID.randomUUID();

        webTestClient.delete().uri("/api/v1/income/sources/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .exchange()
                .expectStatus().isNoContent();

        verify(incomeSourceService).delete(EMAIL, id);
    }

    @Test
    @DisplayName("Sem token, nenhuma rota de renda responde")
    void semTokenNaoResponde() {
        webTestClient.get().uri("/api/v1/income")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(incomeSourceService, never()).overview(any());
    }
}
