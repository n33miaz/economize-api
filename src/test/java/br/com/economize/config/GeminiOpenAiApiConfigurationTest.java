package br.com.economize.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contrato da URL do endpoint OpenAI compatível do Gemini. O servidor falso
 * devolve 503 para também provar que o RetryTemplate vindo da autoconfiguração
 * aplica exatamente o limite configurado.
 */
@SpringBootTest
@DisplayName("OpenAiApi configurado para o endpoint compatível do Gemini")
class GeminiOpenAiApiConfigurationTest {

    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
    private static final AtomicReference<String> REQUEST_PATH = new AtomicReference<>();
    private static final HttpServer SERVER = startServer();

    @Autowired
    private OpenAiApi openAiApi;

    @Autowired
    private OpenAiChatModel chatModel;

    @DynamicPropertySource
    static void aiProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () ->
                "http://" + SERVER.getAddress().getHostString() + ":" + SERVER.getAddress().getPort()
                        + "/v1beta/openai/");
        registry.add("spring.ai.openai.chat.completions-path", () -> "/chat/completions");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
        registry.add("spring.ai.retry.max-attempts", () -> "1");
        registry.add("spring.ai.retry.backoff.initial-interval", () -> "1ms");
        registry.add("spring.ai.retry.backoff.multiplier", () -> "2");
        registry.add("spring.ai.retry.backoff.max-interval", () -> "2ms");
    }

    @BeforeEach
    void resetServer() {
        REQUEST_COUNT.set(0);
        REQUEST_PATH.set(null);
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    @DisplayName("concatena base e completions-path uma vez e não repete 503 (uma tentativa)")
    void shouldUseGeminiCompletionUrlAndConfiguredRetryLimit() {
        // O bean injetado confirma que a chamada abaixo usa o OpenAiApi criado
        // pela autoconfiguração, e não um cliente montado no teste.
        assertThat(openAiApi).isNotNull();

        assertThatThrownBy(() -> chatModel.call(new Prompt(new UserMessage("teste"))))
                .isInstanceOf(RuntimeException.class);

        assertThat(REQUEST_PATH.get()).isEqualTo("/v1beta/openai/chat/completions");
        assertThat(REQUEST_COUNT.get()).isEqualTo(1);
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", GeminiOpenAiApiConfigurationTest::serviceUnavailable);
            server.start();
            return server;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void serviceUnavailable(HttpExchange exchange) throws IOException {
        REQUEST_PATH.set(exchange.getRequestURI().getPath());
        REQUEST_COUNT.incrementAndGet();
        byte[] body = "temporarily unavailable".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(503, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
