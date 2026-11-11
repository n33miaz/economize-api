package br.com.economize.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O IP do cliente atrás do proxy. Cada teste aqui é uma forma de alguém
 * escolher o próprio endereço — e, com ele, o próprio balde de tentativas de
 * login. A cadeia de referência é a medida em produção no Render:
 * {@code <texto do cliente>, <IP real>, <saltos internos 10.x>}.
 */
@DisplayName("ClientIpResolver — o IP que o cliente não escolhe")
class ClientIpResolverTest {

    private static final String PROXY = "10.0.0.9";

    @Test
    @DisplayName("lê da direita para a esquerda e para no primeiro endereço público")
    void firstPublicFromTheRight() {
        assertThat(resolve(PROXY, "1.2.3.4, 203.0.113.7, 10.0.0.5, 10.0.0.9")).isEqualTo("203.0.113.7");
        // sem nada forjado na frente dá no mesmo
        assertThat(resolve(PROXY, "203.0.113.7, 10.0.0.5")).isEqualTo("203.0.113.7");
        // número de saltos internos varia
        assertThat(resolve(PROXY, "203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(resolve(PROXY, "203.0.113.7, 10.1.1.1, 172.16.0.3, 192.168.1.1, 127.0.0.1"))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("o que o cliente forja à esquerda não muda o resultado")
    void forgedLeftSideIsNeverRead() {
        String real = resolve(PROXY, "203.0.113.7, 10.0.0.5");

        assertThat(resolve(PROXY, "9.9.9.9, 203.0.113.7, 10.0.0.5")).isEqualTo(real);
        assertThat(resolve(PROXY, "10.0.0.1, 8.8.8.8, 203.0.113.7, 10.0.0.5")).isEqualTo(real);
        // lixo à esquerda nem chega a ser interpretado
        assertThat(resolve(PROXY, "evil.example, <script>, 203.0.113.7, 10.0.0.5")).isEqualTo(real);
    }

    @Test
    @DisplayName("conexão direta de endereço público ignora o cabeçalho inteiro")
    void publicDirectConnectionIgnoresTheHeader() {
        // sem proxy nosso no caminho, o cabeçalho é só palavra do cliente
        assertThat(resolve("198.51.100.20", "203.0.113.7, 10.0.0.5")).isEqualTo("198.51.100.20");
        assertThat(resolve("198.51.100.20", null)).isEqualTo("198.51.100.20");
    }

    @Test
    @DisplayName("cadeia só com rede interna, vazia ou ausente cai no endereço do soquete")
    void noPublicHopFallsBackToTheSocket() {
        assertThat(resolve(PROXY, "10.0.0.5, 192.168.0.1")).isEqualTo(PROXY);
        assertThat(resolve(PROXY, " , ")).isEqualTo(PROXY);
        assertThat(resolve(PROXY, null)).isEqualTo(PROXY);
    }

    @Test
    @DisplayName("IPv6: público é cliente; ::1, fc00::/7 e fe80::/10 são rede interna")
    void ipv6() {
        assertThat(resolve(PROXY, "2001:db8::bad, 2001:db8:85a3::8a2e:370:7334, fd12:3456::1, fe80::1, ::1"))
                .isEqualTo(address("2001:db8:85a3::8a2e:370:7334"));
        // entre colchetes, como alguns proxies escrevem
        assertThat(resolve(PROXY, "[2001:db8::7], 10.0.0.5")).isEqualTo(address("2001:db8::7"));
        // IPv4 mapeado em IPv6 é o IPv4: interno continua interno
        assertThat(resolve(PROXY, "203.0.113.7, ::ffff:10.0.0.5")).isEqualTo("203.0.113.7");
        // soquete IPv6 interno lê a cadeia; soquete IPv6 público a ignora
        assertThat(resolve("fd00::9", "203.0.113.7, 10.0.0.5")).isEqualTo("203.0.113.7");
        assertThat(resolve("2001:db8::99", "203.0.113.7")).isEqualTo(address("2001:db8::99"));
    }

    @Test
    @DisplayName("salto ilegível no trecho da plataforma não é adivinhado: vale o soquete")
    void unreadableHopFallsBackToTheSocket() {
        // um nome aqui viraria consulta DNS escolhida por quem mandou o cabeçalho
        assertThat(resolve(PROXY, "203.0.113.7, proxy.interno")).isEqualTo(PROXY);
        assertThat(resolve(PROXY, "203.0.113.7, 999.1.1.1")).isEqualTo(PROXY);
    }

    @Test
    @DisplayName("examina no máximo 8 saltos: cadeia interna maior que isso cai no soquete")
    void hopCap() {
        String nove = "203.0.113.7" + ", 10.0.0.1".repeat(ClientIpResolver.MAX_HOPS);
        String sete = "203.0.113.7" + ", 10.0.0.1".repeat(ClientIpResolver.MAX_HOPS - 1);

        assertThat(resolve(PROXY, nove)).isEqualTo(PROXY);
        assertThat(resolve(PROXY, sete)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("cabeçalho repetido em várias linhas é a mesma cadeia, na ordem")
    void repeatedHeaderLines() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress(PROXY, 443))
                .header(ClientIpResolver.HEADER, "1.2.3.4", "203.0.113.7, 10.0.0.5")
                .build();

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("sem soquete e sem cadeia não há IP — nulo, nunca um valor inventado")
    void nothingAtAll() {
        assertThat(ClientIpResolver.resolve(MockServerHttpRequest.get("/").build())).isNull();
        // sem soquete (teste, ambiente estranho) a cadeia ainda é lida
        assertThat(ClientIpResolver.resolve(MockServerHttpRequest.get("/")
                .header(ClientIpResolver.HEADER, "203.0.113.7, 10.0.0.5").build())).isEqualTo("203.0.113.7");
    }

    private static String resolve(String remote, String forwarded) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress(remote, 443));
        if (forwarded != null) builder.header(ClientIpResolver.HEADER, forwarded);
        return ClientIpResolver.resolve(builder.build());
    }

    /** A forma que o Java dá ao literal — o resolvedor devolve o endereço normalizado. */
    private static String address(String literal) {
        try {
            return InetAddress.getByName(literal).getHostAddress();
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
