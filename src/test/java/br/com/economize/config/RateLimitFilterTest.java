package br.com.economize.config;

import io.github.bucket4j.TimeMeter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O 429 deste filtro sai ANTES do Spring Security, que é quem normalmente
 * aplica o CORS. Sem repetir o cabeçalho de origem aqui, o navegador bloqueia a
 * resposta, o app web enxerga "sem resposta" (o mesmo sintoma de servidor
 * dormindo) e reage reenviando a requisição — o rate limit vira "você está
 * offline" e se aprofunda sozinho. Estes testes existem para isso não voltar.
 */
class RateLimitFilterTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:8081";
    private static final String FOREIGN_ORIGIN = "https://site-qualquer.example";

    /** Mesma capacidade do balde padrão do filtro. */
    private static final int STANDARD_CAPACITY = 60;

    /** Relógio que não anda: nenhuma ficha é reposta no meio do teste. */
    private static final TimeMeter RELOGIO_PARADO = new TimeMeter() {
        @Override
        public long currentTimeNanos() {
            return 0L;
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    };

    private RateLimitFilter filter;
    private AtomicInteger chainCalls;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        // Os mesmos tetos do padrao de producao: esta suite existe para
        // travar o COMPORTAMENTO do filtro, e ele precisa ser medido nos
        // numeros que valem no ar.
        //
        // O relogio PARADO nao e detalhe: o balde repoe uma ficha por segundo,
        // entao esgotar 60 exige gastar as 60 em menos de um segundo. Numa
        // maquina ocupada o proprio laco demora mais que isso, o balde repoe no
        // meio e a requisicao seguinte passa -- o teste reprovava medindo a CPU
        // da maquina, e nao o filtro. Foi o que aconteceu em 09/09/2026.
        filter = new RateLimitFilter(corsSource(), 60, 10, RELOGIO_PARADO);
        chainCalls = new AtomicInteger();
        chain = exchange -> {
            chainCalls.incrementAndGet();
            return Mono.empty();
        };
    }

    @Test
    @DisplayName("Preflight passa direto e não gasta token do balde")
    void preflightShouldNotConsumeTokens() {
        for (int i = 0; i < 100; i++) {
            ServerWebExchange preflight = MockServerWebExchange.from(
                    MockServerHttpRequest.method(HttpMethod.OPTIONS, "/api/v1/indicators/all")
                            .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"));

            filter.filter(preflight, chain).block();

            assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, preflight.getResponse().getStatusCode(),
                    "preflight não pode ser barrado: ele mata junto a requisição real que viria atrás");
        }
        assertEquals(100, chainCalls.get());

        // o balde continua cheio: a chamada real gasta o primeiro token agora
        ServerWebExchange real = get(ALLOWED_ORIGIN);
        filter.filter(real, chain).block();

        assertEquals(String.valueOf(STANDARD_CAPACITY - 1),
                real.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
    }

    @Test
    @DisplayName("429 de origem permitida sai COM Access-Control-Allow-Origin")
    void tooManyRequestsShouldCarryCorsHeadersForAllowedOrigin() {
        exhaustBucket();

        ServerWebExchange blocked = get(ALLOWED_ORIGIN);
        filter.filter(blocked, chain).block();
        HttpHeaders headers = blocked.getResponse().getHeaders();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, blocked.getResponse().getStatusCode());
        assertEquals(ALLOWED_ORIGIN, headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN),
                "sem este cabeçalho o navegador esconde o 429 e o app acha que está offline");
        assertTrue(headers.getOrEmpty(HttpHeaders.VARY).contains(HttpHeaders.ORIGIN),
                "cache intermediário não pode servir este 429 para outra origem");
        assertTrue(Integer.parseInt(headers.getFirst(HttpHeaders.RETRY_AFTER)) >= 1,
                "o cliente precisa saber em quantos segundos pode voltar");
        assertEquals(STANDARD_CAPACITY, chainCalls.get(), "requisição barrada não segue para a cadeia");
    }

    @Test
    @DisplayName("429 de origem não declarada continua sem cabeçalho de CORS")
    void tooManyRequestsShouldNotLeakCorsToForeignOrigin() {
        exhaustBucket();

        ServerWebExchange blocked = get(FOREIGN_ORIGIN);
        filter.filter(blocked, chain).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, blocked.getResponse().getStatusCode());
        assertNull(blocked.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN),
                "afrouxar a política de origem não é papel do rate limit");
    }

    @Test
    @DisplayName("Rota cara tem balde próprio e menor, e o 429 dela também leva CORS")
    void expensiveRouteShouldKeepItsOwnBucket() {
        for (int i = 0; i < 10; i++) {
            filter.filter(get(ALLOWED_ORIGIN, "/api/v1/reports"), chain).block();
        }

        ServerWebExchange blocked = get(ALLOWED_ORIGIN, "/api/v1/reports");
        filter.filter(blocked, chain).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, blocked.getResponse().getStatusCode());
        assertEquals(ALLOWED_ORIGIN,
                blocked.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        // a rota barata não foi afetada: são baldes separados
        ServerWebExchange cheap = get(ALLOWED_ORIGIN);
        filter.filter(cheap, chain).block();
        assertEquals(String.valueOf(STANDARD_CAPACITY - 1),
                cheap.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
    }

    @Test
    @DisplayName("O aceite de convite da casa está no balde caro: 10 tentativas e para (EC-149)")
    void familyJoinShouldBeExpensive() {
        for (int i = 0; i < 10; i++) {
            ServerWebExchange attempt = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/family/join").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN));
            filter.filter(attempt, chain).block();
            assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, attempt.getResponse().getStatusCode());
        }

        ServerWebExchange eleventh = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/family/join").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN));
        filter.filter(eleventh, chain).block();

        // O código tem 40 bits; é a taxa, e não o tamanho, que inviabiliza a
        // força bruta. No balde padrão seriam 60 palpites por minuto.
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, eleventh.getResponse().getStatusCode());
        assertEquals(10, chainCalls.get());

        // o resto da família segue no balde padrão, cheio
        ServerWebExchange readFamily = get(ALLOWED_ORIGIN, "/api/v1/family");
        filter.filter(readFamily, chain).block();
        assertEquals(String.valueOf(STANDARD_CAPACITY - 1),
                readFamily.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
    }

    // ------------------------------------------------ memória dos baldes

    @Test
    @DisplayName("tokens Bearer distintos não acumulam baldes sem teto: o mapa tem limite")
    void distinctBearerTokensShouldNotGrowWithoutBound() {
        RateLimitFilter pequeno = new RateLimitFilter(corsSource(), 60, 10, RELOGIO_PARADO, 50);

        // quem manda um token aleatório por requisição criava um balde
        // permanente por token: em 512 MB isso derruba o servidor, e nem
        // precisa de token válido — o filtro roda antes da autenticação
        for (int i = 0; i < 500; i++) {
            pequeno.filter(bearer("token-aleatorio-" + i, "/api/v1/indicators/all"), chain).block();
            pequeno.filter(bearer("token-aleatorio-" + i, "/api/v1/chat"), chain).block();
        }

        // o teto vale para cada uma das duas famílias de balde
        assertThat(pequeno.trackedKeys()).hasSizeLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("o token nunca fica guardado: a chave do balde é o hash dele")
    void rawTokenShouldNotBeRetainedAsKey() {
        // montado em tempo de execução, e não um literal com cara de JWT: o
        // varredor de segredos da esteira lê literal assim como chave vazada
        String miolo = "miolo-" + UUID.randomUUID();
        String credencial = String.join(".", "cabecalho", miolo, "assinatura");

        filter.filter(bearer(credencial, "/api/v1/indicators/all"), chain).block();

        // um dump de memória ou um log da chave não pode entregar a sessão
        assertThat(filter.trackedKeys()).hasSize(1)
                .noneMatch(key -> key.contains(credencial) || key.contains(miolo));
    }

    @Test
    @DisplayName("o mesmo token continua caindo no MESMO balde: o hash não afrouxa o limite")
    void sameTokenShouldKeepSharingOneBucket() {
        for (int i = 0; i < STANDARD_CAPACITY; i++) {
            filter.filter(bearer("token-fixo", "/api/v1/indicators/all"), chain).block();
        }
        ServerWebExchange excedente = bearer("token-fixo", "/api/v1/indicators/all");
        filter.filter(excedente, chain).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, excedente.getResponse().getStatusCode());
        // outro token tem o próprio balde, como antes
        ServerWebExchange outro = bearer("outro-token", "/api/v1/indicators/all");
        filter.filter(outro, chain).block();
        assertThat(outro.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("balde ocioso expira — e só depois de a janela já tê-lo reposto por inteiro")
    void idleBucketShouldExpireOnlyAfterItWouldBeFullAgain() {
        AtomicLong agora = new AtomicLong();
        TimeMeter relogio = new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                return agora.get();
            }

            @Override
            public boolean isWallClockBased() {
                return false;
            }
        };
        RateLimitFilter comRelogio = new RateLimitFilter(corsSource(), 60, 10, relogio, 50);
        for (int i = 0; i < 10; i++) {
            comRelogio.filter(bearer("token-fixo", "/api/v1/chat"), chain).block();
        }

        // 30 s depois o balde caro ainda está lá e ainda barra: expirar cedo
        // seria um jeito de zerar o limite esperando
        agora.set(Duration.ofSeconds(30).toNanos());
        assertThat(comRelogio.trackedKeys()).hasSize(1);

        // passado o tempo de expiração (maior que a janela de 1 min) o balde
        // estaria cheio de qualquer forma: jogá-lo fora não perdoa ninguém
        agora.set(Duration.ofMinutes(10).toNanos());
        assertThat(comRelogio.trackedKeys()).isEmpty();
    }

    // ------------------------------------------------ login: só por IP

    @Test
    @DisplayName("login: Bearer aleatório por requisição NÃO ganha balde novo — a chave é o IP")
    void randomBearerOnLoginShouldNotGetAFreshBucket() {
        // era o contorno do limite de tentativas: o filtro roda antes da
        // autenticação, qualquer texto serve de "token", e cada token novo
        // abria um balde cheio — a senha e o segundo fator ficavam sem teto
        for (int i = 0; i < 10; i++) {
            ServerWebExchange tentativa = login("token-aleatorio-" + i, "203.0.113.7, 10.0.0.5");
            filter.filter(tentativa, chain).block();
            assertThat(tentativa.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
        ServerWebExchange excedente = login("mais-um-token", "203.0.113.7, 10.0.0.5");
        filter.filter(excedente, chain).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, excedente.getResponse().getStatusCode());
        assertThat(filter.trackedKeys()).hasSize(1).noneMatch(key -> key.contains("u:"));
        // outro cliente, atrás do MESMO proxy, tem o próprio balde: o limite
        // não virou um balde único para todo mundo
        ServerWebExchange vizinho = login(null, "198.51.100.44, 10.0.0.5");
        filter.filter(vizinho, chain).block();
        assertThat(vizinho.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("login: X-Forwarded-For forjado à esquerda não muda o balde")
    void forgedForwardedForOnLoginShouldNotChangeTheBucket() {
        for (int i = 0; i < 10; i++) {
            filter.filter(login(null, "9.9.9." + i + ", 203.0.113.7, 10.0.0.5"), chain).block();
        }
        ServerWebExchange excedente = login(null, "1.2.3.4, 203.0.113.7, 10.0.0.5");
        filter.filter(excedente, chain).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, excedente.getResponse().getStatusCode());
        assertThat(filter.trackedKeys()).hasSize(1);
    }

    @Test
    @DisplayName("fora do /auth o Bearer continua sendo a chave, e os baldes de IP ficam em cache separado")
    void bearerStillKeysEverythingElse() {
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/indicators/all")
                .remoteAddress(new InetSocketAddress("10.0.0.9", 443))
                .header("X-Forwarded-For", "203.0.113.7, 10.0.0.5")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-fixo")), chain).block();
        filter.filter(login(null, "203.0.113.7, 10.0.0.5"), chain).block();

        assertThat(filter.trackedKeys()).hasSize(2)
                .anyMatch(key -> key.startsWith("standard:u:"))
                .anyMatch(key -> key.equals("ip-expensive:ip:203.0.113.7"));
    }

    private ServerWebExchange login(String token, String forwardedFor) {
        MockServerHttpRequest.BodyBuilder request = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("10.0.0.9", 443))
                .header("X-Forwarded-For", forwardedFor);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return MockServerWebExchange.from(request);
    }

    private ServerWebExchange bearer(String token, String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void exhaustBucket() {
        for (int i = 0; i < STANDARD_CAPACITY; i++) {
            filter.filter(get(ALLOWED_ORIGIN), chain).block();
        }
    }

    @Test
    @DisplayName("Rota de integração também tem limite, e o balde é por chave (V44)")
    void integracaoTemLimitePorChave() {
        String rota = "/api/v1/integration/shopping/trips";
        String chave = "Bearer eco_" + "A".repeat(43);
        for (int i = 0; i < 60; i++) {
            filter.filter(MockServerWebExchange.from(
                    MockServerHttpRequest.get(rota).header(HttpHeaders.AUTHORIZATION, chave)), chain).block();
        }
        assertEquals(60, chainCalls.get());

        ServerWebExchange barrada = MockServerWebExchange.from(
                MockServerHttpRequest.get(rota).header(HttpHeaders.AUTHORIZATION, chave));
        filter.filter(barrada, chain).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, barrada.getResponse().getStatusCode());
        assertEquals(60, chainCalls.get());

        // Outra chave tem o balde dela: uma integração que exagera não derruba a outra
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get(rota)
                .header(HttpHeaders.AUTHORIZATION, "Bearer eco_" + "B".repeat(43))), chain).block();
        assertEquals(61, chainCalls.get());
    }

    private ServerWebExchange get(String origin) {
        return get(origin, "/api/v1/indicators/all");
    }

    private ServerWebExchange get(String origin, String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path)
                .header(HttpHeaders.ORIGIN, origin));
    }

    /** Mesma configuração que o {@link CorsConfig} publica para o Security. */
    private CorsConfigurationSource corsSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(ALLOWED_ORIGIN));
        configuration.setAllowedMethods(List.of("GET", "POST"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
