package br.com.economize.service.investment;

import br.com.economize.dto.investment.InvestmentResponses;
import br.com.economize.model.PluggyItem;
import br.com.economize.model.User;
import br.com.economize.repository.InvestmentPositionRepository;
import br.com.economize.repository.PluggyItemRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.connector.pluggy.PluggyClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Falha de rede é local ao sync: uma instituição indisponível não apaga as demais. */
@ExtendWith(MockitoExtension.class)
class InvestmentServiceResilienceTest {

    private static final String EMAIL = "dono@economize.app";

    @Mock private InvestmentPositionRepository positions;
    @Mock private PluggyItemRepository items;
    @Mock private UserRepository users;
    @Mock private InvestmentMovementService movements;
    @Mock private ObjectProvider<PluggyClient> clientProvider;
    @Mock private PluggyClient client;
    @Mock private ExchangeRateLookup exchangeRates;

    @Test
    void requestFailureOnOneItemSkipsItAndContinues() {
        User user = User.builder().id(UUID.randomUUID()).email(EMAIL).build();
        PluggyItem first = PluggyItem.builder().itemId("first").connectorName("Primeiro").build();
        PluggyItem second = PluggyItem.builder().itemId("second").connectorName("Segundo").build();
        WebClientRequestException timeout = requestFailure();
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(clientProvider.getIfAvailable()).thenReturn(client);
        when(client.isConfigured()).thenReturn(true);
        when(items.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(first, second));
        when(client.authenticate()).thenReturn("key");
        when(client.investments("key", "first")).thenThrow(timeout);
        when(client.investments("key", "second")).thenReturn(List.of());

        InvestmentResponses.SyncResult result = service().sync(EMAIL);

        assertThat(result.itemsRead()).isEqualTo(1);
        assertThat(result.skippedItems()).isEqualTo(1);
    }

    @Test
    void requestFailureDuringAuthenticationSkipsRegisteredItems() {
        User user = User.builder().id(UUID.randomUUID()).email(EMAIL).build();
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(clientProvider.getIfAvailable()).thenReturn(client);
        when(client.isConfigured()).thenReturn(true);
        when(items.findAllByUserIdOrderByCreatedAtAsc(user.getId())).thenReturn(List.of(
                PluggyItem.builder().itemId("first").build(), PluggyItem.builder().itemId("second").build()));
        when(client.authenticate()).thenThrow(requestFailure());

        InvestmentResponses.SyncResult result = service().sync(EMAIL);

        assertThat(result.itemsRead()).isZero();
        assertThat(result.skippedItems()).isEqualTo(2);
    }

    @Test
    void assistantSummaryReusesPositionCalculationWithoutLoadingMovements() {
        User user = User.builder().id(UUID.randomUUID()).email(EMAIL).build();
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(positions.findAllByUserIdOrderByNameAsc(user.getId())).thenReturn(List.of());

        InvestmentResponses.Summary summary = service().summaryForAssistant(EMAIL);

        assertThat(summary.positionsCount()).isZero();
        verify(movements, never()).movements(user, 12);
    }

    private InvestmentService service() {
        return new InvestmentService(positions, items, users, movements, clientProvider, exchangeRates, 10,
                Clock.systemUTC());
    }

    private static WebClientRequestException requestFailure() {
        return new WebClientRequestException(new IOException("timeout"), HttpMethod.GET,
                URI.create("https://pluggy.test/investments"), HttpHeaders.EMPTY);
    }
}
