package br.com.economize.service.connector.pluggy;

import br.com.economize.exception.UpstreamProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * A Pluggy respondendo 200 sem o token que o contrato promete. Era
 * {@code IllegalStateException} e virava 500 em {@code /connector/sync},
 * {@code /connect-token} e {@code /items}; agora é
 * {@link UpstreamProviderException}, que o handler responde como 502.
 *
 * <p>Fica fora do {@code PluggyClientTest} porque lá o setup prepara o GET, e o
 * Mockito estrito recusaria esses stubs sem uso num teste só de POST.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({ "unchecked", "rawtypes" })
class PluggySyncTokenContractTest {

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestBodyUriSpec bodyUriSpec;

    @Mock
    private WebClient.RequestBodySpec bodySpec;

    @Mock
    private WebClient.RequestHeadersSpec headersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private PluggyClient client;

    @BeforeEach
    void setUp() {
        client = new PluggyClient(webClient, "https://api.pluggy.test", "client-id", "client-secret");
        when(webClient.post()).thenReturn(bodyUriSpec);
        when(bodyUriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                .thenReturn(Mono.just(Map.of("outraCoisa", "x")));
    }

    @Test
    @DisplayName("POST /auth com 200 sem apiKey é falha do provedor, não erro interno")
    void authenticateSemApiKey() {
        assertThatThrownBy(() -> client.authenticate())
                .isInstanceOf(UpstreamProviderException.class);
    }

    @Test
    @DisplayName("POST /connect_token com 200 sem accessToken é falha do provedor, não erro interno")
    void connectTokenSemAccessToken() {
        when(bodySpec.header(anyString(), any(String[].class))).thenReturn(bodySpec);

        assertThatThrownBy(() -> client.connectToken("api-key", "user-1", null))
                .isInstanceOf(UpstreamProviderException.class);
    }
}
