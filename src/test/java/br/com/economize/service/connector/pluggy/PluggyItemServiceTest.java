package br.com.economize.service.connector.pluggy;

import br.com.economize.dto.connector.PluggyItemResponse;
import br.com.economize.exception.ResourceConflictException;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.PluggyItem;
import br.com.economize.model.User;
import br.com.economize.repository.ConnectorAccountRepository;
import br.com.economize.repository.PluggyItemRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluggyItemServiceTest {

    private static final String EMAIL = "teste@economize.app";
    private static final String ITEM_ID = "0f8a8c0e-1111-2222-3333-444455556666";

    @Mock
    private PluggyClient pluggyClient;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PluggyItemRepository pluggyItemRepository;

    @Mock
    private ConnectorAccountRepository accountRepository;

    @InjectMocks
    private PluggyItemService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Teste").password("x").build();
        ReflectionTestUtils.setField(service, "itemIdsRaw", "");
        ReflectionTestUtils.setField(service, "ownerEmail", "");
    }

    @Test
    @DisplayName("connect-token: usa o UUID interno como clientUserId — e-mail (PII) nunca vai ao Pluggy")
    void connectTokenShouldStampInternalIdNotEmail() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyClient.connectToken("api-key", user.getId().toString(), null)).thenReturn("widget-token");

        assertThat(service.connectToken(EMAIL, null)).isEqualTo("widget-token");

        verify(pluggyClient).connectToken("api-key", user.getId().toString(), null);
    }

    @Test
    @DisplayName("connect-token para atualização: itemId que não é do usuário responde 404, não 403")
    void connectTokenShouldHideForeignItems() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyItemRepository.findByItemIdAndUserId(ITEM_ID, user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.connectToken(EMAIL, ITEM_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(pluggyClient, never()).authenticate();
    }

    @Test
    @DisplayName("connect-token sem credenciais da aplicação responde 400 com instrução")
    void connectTokenShouldFailWithoutCredentials() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.connectToken(EMAIL, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLUGGY_CLIENT_ID");
    }

    @Test
    @DisplayName("registro: valida no Pluggy e grava com a instituição copiada do connector")
    void registerShouldValidateAtPluggyAndPersist() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyItemRepository.existsByItemId(ITEM_ID)).thenReturn(false);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyClient.item("api-key", ITEM_ID)).thenReturn(Map.of(
                "id", ITEM_ID,
                "clientUserId", user.getId().toString(),
                "connector", Map.of("id", 201, "name", "Banco Inter")));
        when(pluggyItemRepository.saveAndFlush(any(PluggyItem.class))).thenAnswer(inv -> inv.getArgument(0));

        PluggyItemResponse response = service.register(EMAIL, "  " + ITEM_ID + " ");

        assertThat(response.itemId()).isEqualTo(ITEM_ID);
        assertThat(response.connectorId()).isEqualTo(201L);
        assertThat(response.connectorName()).isEqualTo("Banco Inter");

        ArgumentCaptor<PluggyItem> captor = ArgumentCaptor.forClass(PluggyItem.class);
        verify(pluggyItemRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(user);
    }

    @Test
    @DisplayName("registro de item SEM clientUserId é NEGADO (falha fechada), com o mesmo 404")
    void registerShouldRejectItemWithoutStamp() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyItemRepository.existsByItemId(ITEM_ID)).thenReturn(false);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        // item criado fora do nosso widget (dashboard do Pluggy, outra
        // integração, id herdado da fase de itens globais): sem carimbo não há
        // dono comprovável, e aprovar entregaria o extrato de quem o criou a
        // qualquer conta que descubra o itemId
        when(pluggyClient.item("api-key", ITEM_ID)).thenReturn(Map.of(
                "id", ITEM_ID,
                "connector", Map.of("id", 201, "name", "Banco Inter")));

        assertThatThrownBy(() -> service.register(EMAIL, ITEM_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(pluggyItemRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("registro: corrida de dois POST /items com o mesmo itemId vira 409, nunca 500")
    void registerShouldReturnConflictOnUniqueRace() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        // o duplo toque no onSuccess do widget passa pelos dois existsByItemId
        // antes de qualquer insert: quem perde a corrida bate no unique
        when(pluggyItemRepository.existsByItemId(ITEM_ID)).thenReturn(false);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyClient.item("api-key", ITEM_ID)).thenReturn(Map.of(
                "id", ITEM_ID,
                "clientUserId", user.getId().toString(),
                "connector", Map.of("id", 201, "name", "Banco Inter")));
        when(pluggyItemRepository.saveAndFlush(any(PluggyItem.class)))
                .thenThrow(new DataIntegrityViolationException("uq_pluggy_items_item_id"));

        assertThatThrownBy(() -> service.register(EMAIL, ITEM_ID))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("já está registrado");
    }

    @Test
    @DisplayName("registro de item inexistente no Pluggy responde 404 e não grava nada")
    void registerShouldRejectItemMissingAtPluggy() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyItemRepository.existsByItemId(ITEM_ID)).thenReturn(false);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyClient.item("api-key", ITEM_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.register(EMAIL, ITEM_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(pluggyItemRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("registro de item carimbado para OUTRO usuário responde o mesmo 404 do inexistente")
    void registerShouldRejectItemStampedForAnotherUser() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyItemRepository.existsByItemId(ITEM_ID)).thenReturn(false);
        when(pluggyClient.authenticate()).thenReturn("api-key");
        when(pluggyClient.item("api-key", ITEM_ID)).thenReturn(Map.of(
                "id", ITEM_ID,
                "clientUserId", UUID.randomUUID().toString(),
                "connector", Map.of("id", 201, "name", "Banco Inter")));

        assertThatThrownBy(() -> service.register(EMAIL, ITEM_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(pluggyItemRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("registro de itemId já vinculado responde 409 sem sequer consultar o Pluggy")
    void registerShouldRejectDuplicatedItem() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyClient.isConfigured()).thenReturn(true);
        when(pluggyItemRepository.existsByItemId(ITEM_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.register(EMAIL, ITEM_ID))
                .isInstanceOf(ResourceConflictException.class);

        verify(pluggyClient, never()).authenticate();
    }

    @Test
    @DisplayName("desvincular item de outro usuário responde 404 e não apaga nada")
    void unlinkShouldHideForeignItems() {
        UUID id = UUID.randomUUID();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyItemRepository.findByIdAndUserId(id, user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unlink(EMAIL, id))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(pluggyItemRepository, never()).delete(any());
        verify(pluggyClient, never()).authenticate();
    }

    @Test
    @DisplayName("desvincular remove o vínculo local e apaga o item no Pluggy")
    void unlinkShouldDeleteLocallyAndAtPluggy() {
        UUID id = UUID.randomUUID();
        PluggyItem item = PluggyItem.builder().id(id).user(user).itemId(ITEM_ID).build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyItemRepository.findByIdAndUserId(id, user.getId())).thenReturn(Optional.of(item));
        when(pluggyClient.authenticate()).thenReturn("api-key");

        service.unlink(EMAIL, id);

        verify(pluggyItemRepository).delete(item);
        verify(pluggyClient).deleteItem("api-key", ITEM_ID);
    }

    @Test
    @DisplayName("falha do Pluggy na remoção remota não desfaz nem impede o desvínculo local")
    void unlinkShouldSurviveRemoteFailure() {
        UUID id = UUID.randomUUID();
        PluggyItem item = PluggyItem.builder().id(id).user(user).itemId(ITEM_ID).build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyItemRepository.findByIdAndUserId(id, user.getId())).thenReturn(Optional.of(item));
        when(pluggyClient.authenticate()).thenThrow(new IllegalStateException("Pluggy fora do ar"));

        service.unlink(EMAIL, id);

        verify(pluggyItemRepository).delete(item);
    }

    @Test
    @DisplayName("semente de env: migra só os itens ainda não registrados e pula o que sumiu do Pluggy")
    void seedFromEnvShouldMigrateOnlyPendingItems() {
        ReflectionTestUtils.setField(service, "itemIdsRaw", "env-1, env-2, env-3");
        ReflectionTestUtils.setField(service, "ownerEmail", EMAIL);
        when(pluggyItemRepository.existsByItemId("env-1")).thenReturn(true);
        when(pluggyItemRepository.existsByItemId("env-2")).thenReturn(false);
        when(pluggyItemRepository.existsByItemId("env-3")).thenReturn(false);
        when(pluggyClient.item("api-key", "env-2"))
                .thenReturn(Map.of("connector", Map.of("id", 612, "name", "Nubank")));
        // item que o dono apagou no dashboard: a semente não pode quebrar a sync
        when(pluggyClient.item("api-key", "env-3")).thenReturn(null);
        when(pluggyItemRepository.saveAndFlush(any(PluggyItem.class))).thenAnswer(inv -> inv.getArgument(0));

        service.seedFromEnv(user, "api-key");

        ArgumentCaptor<PluggyItem> captor = ArgumentCaptor.forClass(PluggyItem.class);
        verify(pluggyItemRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getItemId()).isEqualTo("env-2");
        assertThat(captor.getValue().getConnectorName()).isEqualTo("Nubank");
    }

    @Test
    @DisplayName("semente de env: duplicata de sync concorrente é ignorada e NÃO derruba a sincronização")
    void seedFromEnvShouldSurviveConcurrentDuplicate() {
        ReflectionTestUtils.setField(service, "itemIdsRaw", "env-1, env-2");
        ReflectionTestUtils.setField(service, "ownerEmail", EMAIL);
        when(pluggyItemRepository.existsByItemId("env-1")).thenReturn(false);
        when(pluggyItemRepository.existsByItemId("env-2")).thenReturn(false);
        when(pluggyClient.item("api-key", "env-1"))
                .thenReturn(Map.of("connector", Map.of("id", 612, "name", "Nubank")));
        when(pluggyClient.item("api-key", "env-2"))
                .thenReturn(Map.of("connector", Map.of("id", 201, "name", "Banco Inter")));
        // a outra sync do mesmo dono gravou env-1 entre o exists e o insert
        when(pluggyItemRepository.saveAndFlush(any(PluggyItem.class)))
                .thenThrow(new DataIntegrityViolationException("uq_pluggy_items_item_id"))
                .thenAnswer(inv -> inv.getArgument(0));

        // não lança: a semente é idempotente e o segundo item continua entrando
        service.seedFromEnv(user, "api-key");

        verify(pluggyItemRepository, org.mockito.Mockito.times(2)).saveAndFlush(any(PluggyItem.class));
    }

    @Test
    @DisplayName("semente de env nunca roda para quem não é o dono declarado")
    void seedFromEnvShouldIgnoreOtherAccounts() {
        ReflectionTestUtils.setField(service, "itemIdsRaw", "env-1");
        ReflectionTestUtils.setField(service, "ownerEmail", "dono@economize.app");

        service.seedFromEnv(user, "api-key");

        verify(pluggyItemRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("listagem devolve as conexões do usuário sem nenhum segredo")
    void listShouldReturnUserItems() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(pluggyItemRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()))
                .thenReturn(List.of(PluggyItem.builder()
                        .id(UUID.randomUUID()).user(user).itemId(ITEM_ID)
                        .connectorId(201L).connectorName("Banco Inter").build()));

        List<PluggyItemResponse> items = service.list(EMAIL);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).connectorName()).isEqualTo("Banco Inter");
        assertThat(items.get(0).itemId()).isEqualTo(ITEM_ID);
    }
}
