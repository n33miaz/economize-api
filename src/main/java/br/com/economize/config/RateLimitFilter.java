package br.com.economize.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/**
 * Limite de requisições por cliente.
 *
 * <p><b>Por que este filtro cuida de CORS sozinho.</b> Ele roda em
 * {@code HIGHEST_PRECEDENCE + 10}, MUITO antes do {@code WebFilterChainProxy} do
 * Spring Security (ordem -100) — que é quem aplica o
 * {@link CorsConfigurationSource}. Quando o limite estoura e a resposta é
 * curto-circuitada aqui com {@code setComplete()}, a cadeia do Security nunca
 * roda e o 429 sai <b>sem cabeçalho CORS</b>. Para o navegador, resposta sem
 * {@code Access-Control-Allow-Origin} é resposta bloqueada: o axios do app web
 * recebe {@code error.response === undefined}, o mesmo sintoma de servidor
 * dormindo. O cliente então reage exatamente ao contrário do necessário —
 * reenvia a requisição, tenta de novo com backoff e mostra "Sem conexão com o
 * servidor" — o que aprofunda o próprio rate limit numa tempestade
 * auto-alimentada. Por isso o 429 daqui repete o cabeçalho de origem em vez de
 * depender do filtro de CORS que vem depois. Não "simplifique" removendo:
 * enquanto este filtro estiver na frente do Security, quem corta a resposta
 * responde também pelo CORS dela.
 *
 * <p><b>Preflight não consome token.</b> O {@code OPTIONS} de preflight é aperto
 * de mão do navegador, não trabalho da API — e, por não levar
 * {@code Authorization}, cairia no balde POR IP, fazendo cada chamada
 * autenticada da web custar dois tokens em baldes diferentes. Pior: preflight
 * barrado mata a requisição real que viria atrás. Ele passa direto.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter implements WebFilter {

    // O teste de chave do EC-107 entra no balde caro porque cada chamada vira
    // uma requisição paga ao provedor do usuário. O prefixo é o da rota exata:
    // ler o catálogo e a configuração são leituras locais e continuam baratas.
    //
    // O aceite de convite da casa (EC-149) entra pelo motivo oposto: é barato
    // para o servidor e por isso mesmo seria barato de adivinhar. O código tem
    // 40 bits; a 10 tentativas por minuto a força bruta deixa de ser um plano.
    // Só o /join — criar a casa, ler e configurar seguem no balde padrão.
    //
    // Os dois caminhos de login entram porque sao adivinhaveis por definicao: a
    // senha, e depois dela os SEIS DIGITOS do segundo fator. A 10 tentativas por
    // minuto, varrer um milhao de combinacoes leva quase dois anos — e o codigo
    // vale 30 segundos. Sem o balde caro aqui, o segundo fator seria decorativo.
    private static final Set<String> EXPENSIVE_PREFIXES = Set.of(
            "/api/v1/chat", "/api/v1/reports", "/api/v1/ai/settings/test", "/api/v1/family/join",
            "/api/v1/auth/login");

    /** A janela de reposição de todos os baldes. */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * Quanto tempo um balde sem uso fica guardado.
     *
     * <p>Precisa ser MAIOR que a janela: depois de uma janela inteira parado o
     * balde está cheio de novo, então descartá-lo e criar outro dá exatamente
     * no mesmo. Menor que a janela seria um perdão — bastaria esperar a
     * expiração para zerar o limite.
     */
    private static final Duration IDLE_EXPIRY = WINDOW.multipliedBy(2);

    /**
     * Teto de baldes guardados em CADA família (padrão e caro).
     *
     * <p>Este filtro roda antes da autenticação, então a chave vem de um
     * cabeçalho que qualquer um escreve: um token aleatório por requisição
     * criava um balde permanente por token, num mapa sem teto, e o servidor
     * tem 512 MB. Dez mil clientes ativos por família estão muito acima do
     * uso real e custam poucos MB no pior caso. Quando o teto estoura o
     * Caffeine descarta os menos usados — o cliente descartado volta com o
     * balde cheio, que é o erro seguro diante de memória esgotada.
     */
    private static final int MAX_BUCKETS = 10_000;

    private final Cache<String, Bucket> standardBuckets;
    private final Cache<String, Bucket> expensiveBuckets;

    /**
     * Os baldes cuja chave é o IP, em caches PRÓPRIOS.
     *
     * <p>Separados dos de token porque o teto é por cache e quem descarta é o
     * menos usado: no mesmo cache, uma enxurrada de tokens aleatórios (que
     * qualquer um fabrica) expulsaria os baldes de IP — e balde expulso volta
     * cheio. É justamente o de IP que segura as tentativas de login.
     */
    private final Cache<String, Bucket> ipStandardBuckets;
    private final Cache<String, Bucket> ipExpensiveBuckets;

    /**
     * Nestas rotas o {@code Authorization} é IGNORADO e a chave é só o IP.
     *
     * <p>São as rotas de quem ainda não tem sessão: aqui um Bearer não prova
     * nada, é texto livre — e como cada token novo abria um balde cheio,
     * mandar um aleatório por requisição tirava o teto das tentativas de
     * senha e do segundo fator. O IP vem do {@link ClientIpResolver}, que não
     * lê a parte do cabeçalho que o cliente escreve.
     */
    private static final String AUTH_PREFIX = "/api/v1/auth/";

    private final CorsConfigurationSource corsConfigurationSource;

    /**
     * Os tetos por minuto, configuraveis.
     *
     * <p>Sao properties e nao constantes por dois motivos concretos: o operador
     * afina o limite sem esperar um deploy, e a suite de teste — que dispara
     * dezenas de logins do MESMO cliente em segundos — nao precisa de um truque
     * para nao esbarrar no balde. Os padroes sao os valores que valiam antes.
     */
    private final long standardCapacity;
    private final long expensiveCapacity;

    /**
     * O relógio do balde.
     *
     * <p>Existe por causa do TESTE, e o motivo é concreto: o balde repõe fichas
     * continuamente (uma por segundo, no teto padrão), então esgotá-lo exige
     * gastar 60 fichas em menos de um segundo. Numa máquina ocupada o próprio
     * laço do teste demora mais que isso, o balde repõe no meio do caminho, a
     * requisição seguinte passa — e o teste reprova sem que nada no filtro
     * esteja errado. Com um relógio parado, o teste mede o comportamento do
     * balde em vez de medir a CPU da máquina.
     *
     * <p>Em produção é sempre o relógio de parede.
     */
    private final TimeMeter relogio;

    // Com dois construtores o Spring nao escolhe sozinho: procura o vazio,
    // nao acha, e NENHUM contexto sobe. O de producao e este.
    @Autowired
    public RateLimitFilter(CorsConfigurationSource corsConfigurationSource,
                           @Value("${economize.rate-limit.standard-per-minute:60}") long standardCapacity,
                           @Value("${economize.rate-limit.expensive-per-minute:10}") long expensiveCapacity) {
        this(corsConfigurationSource, standardCapacity, expensiveCapacity, TimeMeter.SYSTEM_MILLISECONDS);
    }

    RateLimitFilter(CorsConfigurationSource corsConfigurationSource,
                    long standardCapacity,
                    long expensiveCapacity,
                    TimeMeter relogio) {
        this(corsConfigurationSource, standardCapacity, expensiveCapacity, relogio, MAX_BUCKETS);
    }

    RateLimitFilter(CorsConfigurationSource corsConfigurationSource,
                    long standardCapacity,
                    long expensiveCapacity,
                    TimeMeter relogio,
                    int maxBuckets) {
        this.corsConfigurationSource = corsConfigurationSource;
        this.standardCapacity = standardCapacity;
        this.expensiveCapacity = expensiveCapacity;
        this.relogio = relogio;
        this.standardBuckets = newBucketCache(relogio, maxBuckets);
        this.expensiveBuckets = newBucketCache(relogio, maxBuckets);
        this.ipStandardBuckets = newBucketCache(relogio, maxBuckets);
        this.ipExpensiveBuckets = newBucketCache(relogio, maxBuckets);
    }

    /**
     * O relógio da expiração é o MESMO do balde, pelo mesmo motivo: o teste
     * mede o filtro, não a máquina. A manutenção roda na própria thread da
     * chamada ({@code Runnable::run}) para o teto valer na hora, sem depender
     * de um pool que ninguém configurou.
     */
    private static Cache<String, Bucket> newBucketCache(TimeMeter relogio, int maxBuckets) {
        return Caffeine.newBuilder()
                .maximumSize(maxBuckets)
                .expireAfterAccess(IDLE_EXPIRY)
                .ticker(relogio::currentTimeNanos)
                .executor(Runnable::run)
                .build();
    }

    /** As chaves guardadas agora, de todos os caches — só para o teste conferir teto e conteúdo. */
    Set<String> trackedKeys() {
        Set<String> keys = new HashSet<>();
        collectKeys(keys, "standard:", standardBuckets);
        collectKeys(keys, "expensive:", expensiveBuckets);
        collectKeys(keys, "ip-standard:", ipStandardBuckets);
        collectKeys(keys, "ip-expensive:", ipExpensiveBuckets);
        return keys;
    }

    private static void collectKeys(Set<String> keys, String prefix, Cache<String, Bucket> cache) {
        cache.cleanUp();
        cache.asMap().keySet().forEach(key -> keys.add(prefix + key));
    }

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        if (CorsUtils.isPreFlightRequest(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        String key = clientKey(exchange, path);
        boolean expensive = EXPENSIVE_PREFIXES.stream().anyMatch(path::startsWith);
        boolean byToken = key.startsWith(TOKEN_KEY_PREFIX);

        Cache<String, Bucket> buckets = expensive
                ? (byToken ? expensiveBuckets : ipExpensiveBuckets)
                : (byToken ? standardBuckets : ipStandardBuckets);
        Bucket bucket = buckets.get(key,
                k -> createBucket(expensive ? expensiveCapacity : standardCapacity, WINDOW));

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            exchange.getResponse().getHeaders().set("X-RateLimit-Remaining",
                    String.valueOf(probe.getRemainingTokens()));
            return chain.filter(exchange);
        }

        long retrySeconds = probe.getNanosToWaitForRefill() / 1_000_000_000L;
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(retrySeconds, 1)));
        applyCorsHeaders(exchange);
        return response.setComplete();
    }

    /**
     * Repete na resposta cortada o mesmo julgamento de origem que o Security
     * faria: origem não declarada em {@code CORS_ALLOWED_ORIGINS} continua sem
     * cabeçalho nenhum (não é papel do rate limit afrouxar a política), e origem
     * permitida recebe o {@code Access-Control-Allow-Origin} que faz o navegador
     * entregar o 429 ao app — que aí trata "muitas requisições" em vez de achar
     * que está offline. O {@code Vary: Origin} evita que um proxy sirva a mesma
     * resposta 429 para outra origem.
     */
    private void applyCorsHeaders(ServerWebExchange exchange) {
        String origin = exchange.getRequest().getHeaders().getOrigin();
        if (origin == null) {
            return;
        }
        CorsConfiguration configuration = corsConfigurationSource.getCorsConfiguration(exchange);
        if (configuration == null) {
            return;
        }
        String allowedOrigin = configuration.checkOrigin(origin);
        if (allowedOrigin == null) {
            return;
        }
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin);
        headers.add(HttpHeaders.VARY, HttpHeaders.ORIGIN);
        if (Boolean.TRUE.equals(configuration.getAllowCredentials())) {
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }
    }

    private Bucket createBucket(long capacity, Duration window) {
        return Bucket.builder()
                .withCustomTimePrecision(relogio)
                .addLimit(Bandwidth.builder().capacity(capacity).refillGreedy(capacity, window).build())
                .build();
    }

    private static final String TOKEN_KEY_PREFIX = "u:";

    private String clientKey(ServerWebExchange exchange, String path) {
        ServerHttpRequest request = exchange.getRequest();
        if (!path.startsWith(AUTH_PREFIX)) {
            String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (auth != null && auth.startsWith("Bearer ")) return TOKEN_KEY_PREFIX + hash(auth.substring(7));
        }
        // O endereço do soquete, atrás do proxy, é o do PROXY: usá-lo como
        // chave punha todo mundo sem token no mesmo balde. O resolvedor acha
        // o cliente na cadeia sem ler o que o próprio cliente escreveu nela
        String ip = ClientIpResolver.resolve(request);
        return ip != null ? "ip:" + ip : "anon";
    }

    /**
     * A chave do balde é o SHA-256 do token, nunca o token.
     *
     * <p>Dois motivos: o token é uma credencial viva e não tem por que ficar
     * parado na memória de um filtro (dump, log de chave), e o tamanho da
     * chave deixa de ser escolhido por quem manda o cabeçalho — um "token" de
     * vários KB ocupava esse tanto por entrada.
     */
    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 é obrigatório em toda JVM; chegar aqui é ambiente quebrado
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
