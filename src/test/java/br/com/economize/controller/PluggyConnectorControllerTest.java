package br.com.economize.controller;

import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import br.com.economize.exception.UpstreamProviderException;
import java.net.URI;
import java.util.concurrent.TimeoutException;
import br.com.economize.config.CorsConfig;
import br.com.economize.dto.connector.ConnectionResponse;
import br.com.economize.dto.connector.RegisterPluggyItemRequest;
import br.com.economize.exception.ResourceConflictException;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.security.JwtAuthenticationFilter;
import br.com.economize.security.JwtUtil;
import br.com.economize.security.SecurityConfig;
import br.com.economize.service.BankStatementService;
import br.com.economize.service.connector.OpenFinanceProvider;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A rota legada {@code /connectors/pluggy/**} é o contrato do APK 2.2.0: continua
 * respondendo o mesmo shape, agora delegando ao {@link OpenFinanceProvider}.
 */
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
    private OpenFinanceProvider provider;

    @BeforeEach
    void provedorLigado() {
        when(provider.enabled()).thenReturn(true);
    }

    @Test
    @DisplayName("GET /status - Contrato do APK publicado preservado (enabled/owner/configured/itemCount)")
    void statusShouldKeepPublishedContract() {
        when(provider.status(EMAIL)).thenReturn(new OpenFinanceProvider.ProviderStatus(true, true, 2));

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
        when(provider.connectToken(EMAIL, null)).thenReturn("widget-token");

        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/connect-token")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isEqualTo("widget-token")
                .jsonPath("$.apiKey").doesNotExist()
                .jsonPath("$.clientSecret").doesNotExist();

        verify(provider).connectToken(EMAIL, null);
    }

    @Test
    @DisplayName("POST /connect-token?itemId= - Repassa o item para o widget abrir em modo atualização")
    void connectTokenShouldPassItemIdForUpdateMode() {
        when(provider.connectToken(EMAIL, ITEM_ID)).thenReturn("widget-token");

        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/connect-token?itemId=" + ITEM_ID)
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk();

        verify(provider).connectToken(EMAIL, ITEM_ID);
    }

    @Test
    @DisplayName("POST /connect-token - Sem token deve retornar 401")
    void connectTokenShouldRequireAuthentication() {
        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/connect-token")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(provider, never()).connectToken(any(), any());
    }

    @Test
    @DisplayName("POST /items - Registro devolve 201 com a conexão criada (e o novo campo institution)")
    void registerShouldReturnCreated() {
        when(provider.registerItem(EMAIL, ITEM_ID)).thenReturn(ConnectionResponse.of(
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
                .jsonPath("$.institution").isEqualTo("Banco Inter")
                .jsonPath("$.lastSyncedAt").isEmpty();

        verify(provider).registerItem(EMAIL, ITEM_ID);
    }

    @Test
    @DisplayName("POST /items - itemId em branco responde 400 sem chamar o provedor")
    void registerShouldRejectBlankItemId() {
        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/items")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RegisterPluggyItemRequest("  "))
                .exchange()
                .expectStatus().isBadRequest();

        verify(provider, never()).registerItem(anyString(), anyString());
    }

    @Test
    @DisplayName("POST /items - Item que não existe no provedor responde 404 ProblemDetail")
    void registerShouldReturnNotFoundWhenItemMissingAtPluggy() {
        when(provider.registerItem(EMAIL, ITEM_ID)).thenThrow(new ResourceNotFoundException(
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
        when(provider.registerItem(EMAIL, ITEM_ID))
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
        when(provider.listItems(EMAIL)).thenReturn(List.of(ConnectionResponse.of(
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

        verify(provider).unlinkItem(EMAIL, id);
    }

    @Test
    @DisplayName("DELETE /items/{id} - Item de outro usuário responde 404, nunca 403")
    void unlinkShouldReturnNotFoundForForeignItem() {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Item não encontrado"))
                .when(provider).unlinkItem(EMAIL, id);

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
        UUID segundoUploadId = UUID.randomUUID();
        when(provider.sync(EMAIL, 90)).thenReturn(new OpenFinanceProvider.SyncResult(
                new BankStatementService.ImportResult(uploadId, 12, 7, 2, 3, false, "PLUGGY",
                        java.util.List.of(uploadId, segundoUploadId)), 2));

        webTestClient.post()
                .uri("/api/v1/connectors/pluggy/sync")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.uploadId").isEqualTo(uploadId.toString())
                .jsonPath("$.uploadIds[0]").isEqualTo(uploadId.toString())
                .jsonPath("$.uploadIds[1]").isEqualTo(segundoUploadId.toString())
                .jsonPath("$.transactionsImported").isEqualTo(12)
                .jsonPath("$.suggested").isEqualTo(7)
                .jsonPath("$.uncategorized").isEqualTo(2)
                .jsonPath("$.reconciled").isEqualTo(3)
                .jsonPath("$.format").isEqualTo("PLUGGY")
                .jsonPath("$.itemsSynced").isEqualTo(2);

        verify(provider).sync(EMAIL, 90);
    }

    @Test
    @DisplayName("POST /sync - Sem conexões registradas responde 400 com orientação")
    void syncShouldReturnBadRequestWithoutItems() {
        when(provider.sync(EMAIL, 90)).thenThrow(new IllegalArgumentException(
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

    @Test
    @DisplayName("POST /connect-token - Pluggy sem responder é 503, sem a URL do provedor na resposta")
    void connectTokenShouldAnswer503WhenProviderIsDown() {
        when(provider.connectToken(EMAIL, null)).thenThrow(new WebClientRequestException(
                new TimeoutException("read timed out"), HttpMethod.POST,
                URI.create("https://api.pluggy.ai/auth"), new HttpHeaders()));

        String corpo = webTestClient.post()
                .uri("/api/v1/connectors/pluggy/connect-token")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody(String.class).returnResult().getResponseBody();

        org.assertj.core.api.Assertions.assertThat(corpo)
                .contains("O provedor não respondeu")
                .doesNotContain("pluggy.ai");
    }

    @Test
    @DisplayName("POST /connect-token - Pluggy 200 sem token é 502, sem citar variável de ambiente")
    void connectTokenShouldAnswer502WhenProviderOmitsToken() {
        when(provider.connectToken(EMAIL, null)).thenThrow(
                new UpstreamProviderException("Pluggy não devolveu apiKey — confira PLUGGY_CLIENT_ID/SECRET"));

        String corpo = webTestClient.post()
                .uri("/api/v1/connectors/pluggy/connect-token")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody(String.class).returnResult().getResponseBody();

        org.assertj.core.api.Assertions.assertThat(corpo).doesNotContain("PLUGGY_CLIENT");
    }
}
