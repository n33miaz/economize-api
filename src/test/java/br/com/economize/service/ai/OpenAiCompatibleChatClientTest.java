package br.com.economize.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class OpenAiCompatibleChatClientTest {

    private static final String CHAVE = "sk-usuario-super-secreta-1234567890abcdef";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final AtomicReference<ClientRequest> captured = new AtomicReference<>();

    private AiCallTarget target(Integer maxTokens) {
        return new AiCallTarget(AiProvider.OPENAI, "gpt-4o-mini",
                "https://example.test/openai/chat/completions", CHAVE, maxTokens);
    }

    private OpenAiCompatibleChatClient clientAnswering(HttpStatus status, String body) {
        ExchangeFunction exchange = request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(status)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        };
        return new OpenAiCompatibleChatClient(WebClient.builder().exchangeFunction(exchange).build());
    }

    @Test
    @DisplayName("Resposta feliz: devolve o texto de choices[0].message.content")
    void shouldReturnContent() {
        OpenAiCompatibleChatClient client = clientAnswering(HttpStatus.OK,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"olá\"}}]}");

        assertThat(client.complete(target(null), "sistema", "usuário", TIMEOUT)).isEqualTo("olá");
    }

    @Test
    @DisplayName("A chave do usuário vai no Authorization, e o modelo escolhido no corpo")
    void shouldSendUserKeyAndModel() {
        OpenAiCompatibleChatClient client = clientAnswering(HttpStatus.OK,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");

        client.complete(target(null), "sistema", "usuário", TIMEOUT);

        ClientRequest request = captured.get();
        assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + CHAVE);
        assertThat(request.url().toString()).isEqualTo("https://example.test/openai/chat/completions");
        assertThat(bodyOf(request))
                .contains("\"model\":\"gpt-4o-mini\"")
                .contains("\"role\":\"system\"")
                .contains("\"role\":\"user\"");
    }

    @Test
    @DisplayName("max_tokens só é enviado quando o provedor exige (Anthropic); nos outros nem aparece")
    void shouldSendMaxTokensOnlyWhenConfigured() {
        OpenAiCompatibleChatClient semTeto = clientAnswering(HttpStatus.OK,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        semTeto.complete(target(null), "s", "u", TIMEOUT);
        assertThat(bodyOf(captured.get())).doesNotContain("max_tokens");

        OpenAiCompatibleChatClient comTeto = clientAnswering(HttpStatus.OK,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        comTeto.complete(target(4096), "s", "u", TIMEOUT);
        assertThat(bodyOf(captured.get())).contains("\"max_tokens\":4096");
    }

    @Test
    @DisplayName("401 vira AUTH — e a mensagem não carrega a chave nem o corpo do provedor")
    void shouldClassifyUnauthorizedWithoutLeakingTheKey() {
        OpenAiCompatibleChatClient client = clientAnswering(HttpStatus.UNAUTHORIZED,
                "{\"error\":{\"message\":\"Incorrect API key provided: " + CHAVE + "\"}}");

        AiProviderException erro = catchThrowableOfType(
                () -> client.complete(target(null), "s", "u", TIMEOUT), AiProviderException.class);

        assertThat(erro).isNotNull();
        assertThat(erro.getReason()).isEqualTo(AiProviderException.Reason.AUTH);
        assertThat(erro.getMessage()).doesNotContain(CHAVE);
        assertThat(erro.getMessage()).doesNotContain("Incorrect API key");
    }

    @Test
    @DisplayName("404 e 400 viram MODEL; 429 vira RATE_LIMIT; 500 vira PROVIDER")
    void shouldClassifyOtherStatuses() {
        assertThat(reasonFor(HttpStatus.NOT_FOUND)).isEqualTo(AiProviderException.Reason.MODEL);
        assertThat(reasonFor(HttpStatus.BAD_REQUEST)).isEqualTo(AiProviderException.Reason.MODEL);
        assertThat(reasonFor(HttpStatus.TOO_MANY_REQUESTS)).isEqualTo(AiProviderException.Reason.RATE_LIMIT);
        assertThat(reasonFor(HttpStatus.INTERNAL_SERVER_ERROR)).isEqualTo(AiProviderException.Reason.PROVIDER);
    }

    @Test
    @DisplayName("Falha de transporte vira NETWORK, sem repassar a exceção original")
    void shouldClassifyNetworkFailure() {
        ExchangeFunction exchange = request -> Mono.error(new java.net.ConnectException("connection refused"));
        OpenAiCompatibleChatClient client =
                new OpenAiCompatibleChatClient(WebClient.builder().exchangeFunction(exchange).build());

        AiProviderException erro = catchThrowableOfType(
                () -> client.complete(target(null), "s", "u", TIMEOUT), AiProviderException.class);

        assertThat(erro).isNotNull();
        assertThat(erro.getReason()).isEqualTo(AiProviderException.Reason.NETWORK);
    }

    @Test
    @DisplayName("Resposta sem conteúdo utilizável vira erro classificado, não string vazia")
    void shouldRejectEmptyContent() {
        OpenAiCompatibleChatClient client = clientAnswering(HttpStatus.OK, "{\"choices\":[]}");

        assertThatThrownBy(() -> client.complete(target(null), "s", "u", TIMEOUT))
                .isInstanceOf(AiProviderException.class);
    }

    @Test
    @DisplayName("Conteúdo em lista de partes também é lido")
    void shouldReadContentParts() {
        OpenAiCompatibleChatClient client = clientAnswering(HttpStatus.OK,
                "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"a\"},"
                        + "{\"type\":\"text\",\"text\":\"b\"}]}}]}");

        assertThat(client.complete(target(null), "s", "u", TIMEOUT)).isEqualTo("ab");
    }

    @Test
    @DisplayName("A redação apaga a chave inteira, o prefixo e o sufixo antes de qualquer log")
    void redactShouldRemoveEveryTraceOfTheKey() {
        String corpo = "erro com " + CHAVE + " e mascarada " + CHAVE.substring(0, 8) + "..."
                + CHAVE.substring(CHAVE.length() - 4);

        String redigido = OpenAiCompatibleChatClient.redact(corpo, CHAVE);

        assertThat(redigido).doesNotContain(CHAVE);
        assertThat(redigido).doesNotContain(CHAVE.substring(0, 8));
        assertThat(redigido).doesNotContain(CHAVE.substring(CHAVE.length() - 4));
        assertThat(redigido).contains("***");
    }

    @Test
    @DisplayName("A redação corta corpo gigante em vez de despejá-lo no log")
    void redactShouldTruncate() {
        String enorme = "x".repeat(5000);

        assertThat(OpenAiCompatibleChatClient.redact(enorme, CHAVE)).hasSizeLessThan(400);
    }

    @Test
    @DisplayName("AiCallTarget nunca imprime a chave — nem via toString automático de record")
    void targetToStringShouldHideTheKey() {
        assertThat(target(null).toString())
                .doesNotContain(CHAVE)
                .contains("chave=oculta");
    }

    private AiProviderException.Reason reasonFor(HttpStatus status) {
        OpenAiCompatibleChatClient client = clientAnswering(status, "{\"error\":\"qualquer\"}");
        AiProviderException erro = catchThrowableOfType(
                () -> client.complete(target(null), "s", "u", TIMEOUT), AiProviderException.class);
        return erro == null ? null : erro.getReason();
    }

    /** Serializa o corpo da requisição capturada para poder afirmar sobre ele. */
    private static String bodyOf(ClientRequest request) {
        MockClientHttpRequest mock = new MockClientHttpRequest(request.method(), request.url());
        ExchangeStrategies strategies = ExchangeStrategies.withDefaults();
        request.body().insert(mock, new BodyInserter.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return strategies.messageWriters();
            }

            @Override
            public Optional<ServerHttpRequest> serverRequest() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> hints() {
                return Map.of();
            }
        }).block();
        return mock.getBodyAsString().block();
    }
}
