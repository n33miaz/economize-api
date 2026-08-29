package br.com.economize.controller;

import br.com.economize.config.CorsConfig;
import br.com.economize.dto.connector.PluggyItemResponse;
import br.com.economize.dto.connector.RegisterPluggyItemRequest;
import br.com.economize.exception.ResourceConflictException;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.BankStatementService;
import br.com.economize.service.connector.pluggy.PluggyItemService;
import br.com.economize.service.connector.pluggy.PluggySyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest(PluggyConnectorController.class)
@Import({ CorsConfig.class, SecurityConfig.class, JwtUtil.class, JwtAuthenticationFilter.class})
class PluggyConnectorControllerTest {

    private static final String EMAIL = "teste@economize.app";
    private static final String ITEM_ID = "0f8a8c0e-1111-2222-3333-444455556666";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private PluggySyncService syncService;

    @MockitoBean
    private PluggyItemService itemService;

    @Test
    @DisplayName("GET /status - Contrato do APK publicado preservado (enabled/owner/configured/itemCount)")
    void statusShouldKeepPublishedContract() {
        when(syncService.status(EMAIL)).thenReturn(Map.of(
                "enabled", true, "owner", true, "configured", true, "itemCount", 2L));

        webTestClient.get()
                .uri("/api/v1/connectors/pluggy/status")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.owner").isEqualTo(true)
                .jsonPath("$.configured").isEqualTo(true)
                .jsonPath("$.itemCount").isEqualTo(2);
    }

    @Test
    @DisplayName("POST /connect-token - Devolve o accessToken do widget e nada além dele")
    void connectTokenShouldReturnAccessToken() {
        when(itemService.connectToken(EMAIL, null)).thenReturn("widget-token");

        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/connect-token")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isEqualTo("widget-token")
                .jsonPath("$.apiKey").doesNotExist()
                .jsonPath("$.clientSecret").doesNotExist();

        verify(itemService).connectToken(EMAIL, null);
    }

    @Test
    @DisplayName("POST /connect-token?itemId= - Repassa o item para o widget abrir em modo atualização")
    void connectTokenShouldPassItemIdForUpdateMode() {
        when(itemService.connectToken(EMAIL, ITEM_ID)).thenReturn("widget-token");

        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/connect-token?itemId=" + ITEM_ID)
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk();

        verify(itemService).connectToken(EMAIL, ITEM_ID);
    }

    @Test
    @DisplayName("POST /connect-token - Sem token deve retornar 401")
    void connectTokenShouldRequireAuthentication() {
        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/connect-token")
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(itemService);
    }

    @Test
    @DisplayName("POST /items - Registro devolve 201 com a conexão criada")
    void registerShouldReturnCreated() {
        when(itemService.register(EMAIL, ITEM_ID)).thenReturn(new PluggyItemResponse(
                UUID.randomUUID(), ITEM_ID, 201L, "Banco Inter",
                OffsetDateTime.parse("2026-08-15T12:00:00Z"), null));

        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/items")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RegisterPluggyItemRequest(ITEM_ID))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.itemId").isEqualTo(ITEM_ID)
                .jsonPath("$.connectorName").isEqualTo("Banco Inter")
                .jsonPath("$.lastSyncedAt").isEmpty();

        verify(itemService).register(EMAIL, ITEM_ID);
    }

    @Test
    @DisplayName("POST /items - itemId em branco responde 400 sem chamar o service")
    void registerShouldRejectBlankItemId() {
        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/items")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RegisterPluggyItemRequest("  "))
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(itemService);
    }

    @Test
    @DisplayName("POST /items - Item que não existe no Pluggy responde 404 ProblemDetail")
    void registerShouldReturnNotFoundWhenItemMissingAtPluggy() {
        when(itemService.register(EMAIL, ITEM_ID)).thenThrow(new ResourceNotFoundException(
                "Item não encontrado no Pluggy — conclua a conexão no widget antes de registrar"));

        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/items")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RegisterPluggyItemRequest(ITEM_ID))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Não Encontrado");
    }

    @Test
    @DisplayName("POST /items - itemId já vinculado responde 409")
    void registerShouldReturnConflictWhenAlreadyRegistered() {
        when(itemService.register(EMAIL, ITEM_ID))
                .thenThrow(new ResourceConflictException("Este item já está registrado"));

        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/items")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RegisterPluggyItemRequest(ITEM_ID))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("GET /items - Lista as conexões do usuário")
    void listShouldReturnUserItems() {
        when(itemService.list(EMAIL)).thenReturn(List.of(new PluggyItemResponse(
                UUID.randomUUID(), ITEM_ID, 612L, "Nubank",
                OffsetDateTime.parse("2026-08-15T12:00:00Z"),
                OffsetDateTime.parse("2026-08-15T13:00:00Z"))));

        webTestClient.get()
                .uri("/api/v1/connectors/pluggy/items")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].itemId").isEqualTo(ITEM_ID)
                .jsonPath("$[0].connectorName").isEqualTo("Nubank");
    }

    @Test
    @DisplayName("DELETE /items/{id} - Desvincula e responde 204")
    void unlinkShouldReturnNoContent() {
        UUID id = UUID.randomUUID();

        webTestClient.delete()
                .uri("/api/v1/connectors/pluggy/items/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isNoContent();

        verify(itemService).unlink(EMAIL, id);
    }

    @Test
    @DisplayName("DELETE /items/{id} - Item de outro usuário responde 404, nunca 403")
    void unlinkShouldReturnNotFoundForForeignItem() {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Item não encontrado"))
                .when(itemService).unlink(EMAIL, id);

        webTestClient.delete()
                .uri("/api/v1/connectors/pluggy/items/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Não Encontrado");
    }

    @Test
    @DisplayName("POST /sync - Mantém os campos do contrato publicado e soma itemsSynced")
    void syncShouldKeepPublishedContractAndAddItemsSynced() {
        UUID uploadId = UUID.randomUUID();
        when(syncService.sync(EMAIL, 90)).thenReturn(new PluggySyncService.SyncResult(
                new BankStatementService.ImportResult(uploadId, 12, 7, 2, 3, false, "PLUGGY"), 2));

        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/sync")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.uploadId").isEqualTo(uploadId.toString())
                .jsonPath("$.transactionsImported").isEqualTo(12)
                .jsonPath("$.suggested").isEqualTo(7)
                .jsonPath("$.uncategorized").isEqualTo(2)
                .jsonPath("$.reconciled").isEqualTo(3)
                .jsonPath("$.format").isEqualTo("PLUGGY")
                .jsonPath("$.itemsSynced").isEqualTo(2);

        verify(syncService).sync(EMAIL, 90);
    }

    @Test
    @DisplayName("POST /sync - Sem conexões registradas responde 400 com orientação")
    void syncShouldReturnBadRequestWithoutItems() {
        when(syncService.sync(EMAIL, 90)).thenThrow(new IllegalArgumentException(
                "Nenhuma conexão Pluggy registrada — conecte uma instituição pelo app antes de sincronizar"));

        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/sync")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(detail ->
                        org.assertj.core.api.Assertions.assertThat((String) detail)
                                .contains("Nenhuma conexão Pluggy"));
    }

    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken(EMAIL);
    }
}
