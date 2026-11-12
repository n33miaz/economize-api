package br.com.economize.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O IP do cliente atrás do proxy. Cada teste aqui é uma forma de alguém
 * escolher o próprio endereço — e, com ele, o próprio balde de tentativas de
 * login. A cadeia de referência é a medida em produção no Render:
 * {@code <texto do cliente>, <IP real>, <borda da Cloudflare>, <saltos
 * internos 10.x>}.
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
    @DisplayName("borda da Cloudflare é proxy: o cliente é o salto à esquerda dela")
    void cloudflareEdgeIsSkipped() {
        assertThat(resolve(PROXY, "1.2.3.4, 203.0.113.7, 172.71.10.5, 10.0.0.9")).isEqualTo("203.0.113.7");
        // as bordas medidas em produção
        for (String edge : List.of("104.22.1.9", "104.23.200.1", "172.64.0.1", "172.68.33.4", "172.71.255.254")) {
            assertThat(resolve(PROXY, "203.0.113.7, " + edge + ", 10.0.0.9")).as(edge).isEqualTo("203.0.113.7");
        }
        // a borda muda a cada requisição; o cliente, não
        assertThat(resolve(PROXY, "203.0.113.7, 104.22.1.9, 10.0.0.9"))
                .isEqualTo(resolve(PROXY, "203.0.113.7, 172.68.33.4, 10.0.0.2, 10.0.0.9"));
    }

    @Test
    @DisplayName("forjado à esquerda não muda o resultado, com a borda no caminho")
    void forgedLeftSideWithEdge() {
        String real = resolve(PROXY, "203.0.113.7, 172.71.10.5, 10.0.0.9");

        assertThat(real).isEqualTo("203.0.113.7");
        assertThat(resolve(PROXY, "9.9.9.9, 203.0.113.7, 172.71.10.5, 10.0.0.9")).isEqualTo(real);
        // forjar uma "borda" ou uma rede interna à esquerda também não adianta
        assertThat(resolve(PROXY, "172.71.1.1, 203.0.113.7, 172.71.10.5, 10.0.0.9")).isEqualTo(real);
        assertThat(resolve(PROXY, "172.71.1.1, 10.0.0.1, 203.0.113.7, 172.71.10.5, 10.0.0.9")).isEqualTo(real);
        assertThat(resolve(PROXY, "evil.example, 203.0.113.7, 172.71.10.5, 10.0.0.9")).isEqualTo(real);
    }

    @Test
    @DisplayName("cadeia só com a borda e rede interna cai no soquete")
    void onlyEdgeAndInternalFallsBackToTheSocket() {
        assertThat(resolve(PROXY, "172.71.10.5, 10.0.0.9")).isEqualTo(PROXY);
        assertThat(resolve(PROXY, "10.0.0.1, 172.71.10.5, 10.0.0.5, 10.0.0.9")).isEqualTo(PROXY);
        assertThat(resolve(PROXY, "2606:4700::1, 10.0.0.9")).isEqualTo(PROXY);
    }

    @Test
    @DisplayName("soquete em faixa da Cloudflare é proxy: a cadeia é lida")
    void cloudflareSocketReadsTheChain() {
        assertThat(resolve("172.71.10.5", "1.2.3.4, 203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(resolve("2606:4700::1", "203.0.113.7, 10.0.0.5")).isEqualTo("203.0.113.7");
        // sem cadeia, vale o próprio soquete
        assertThat(resolve("172.71.10.5", null)).isEqualTo("172.71.10.5");
        // o soquete É a borda: o salto seguinte já é o cliente, mesmo em faixa da Cloudflare
        assertThat(resolve("172.71.10.5", "9.9.9.9, 104.16.5.5")).isEqualTo("104.16.5.5");
    }

    @Test
    @DisplayName("IPv6 da Cloudflare também é pulado")
    void cloudflareIpv6() {
        assertThat(resolve(PROXY, "2001:db8::7, 2606:4700:10::6816:1, 10.0.0.9")).isEqualTo(address("2001:db8::7"));
        assertThat(resolve(PROXY, "203.0.113.7, 2a06:98c7:ffff::1, 10.0.0.9")).isEqualTo("203.0.113.7");
        assertThat(resolve(PROXY, "203.0.113.7, [2400:cb00::1], 10.0.0.9")).isEqualTo("203.0.113.7");
        // IPv4 da Cloudflare mapeado em IPv6 é o IPv4
        assertThat(resolve(PROXY, "203.0.113.7, ::ffff:172.71.10.5, 10.0.0.9")).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("CIDR casa exatamente nas bordas das faixas")
    void cidrBoundaries() {
        // 172.64.0.0/13 = 172.64.0.0 a 172.71.255.255
        assertThat(ClientIpResolver.classify("172.63.255.255")).isEqualTo(ClientIpResolver.HopKind.PUBLIC);
        assertThat(ClientIpResolver.classify("172.64.0.0")).isEqualTo(ClientIpResolver.HopKind.CLOUDFLARE);
        assertThat(ClientIpResolver.classify("172.71.255.255")).isEqualTo(ClientIpResolver.HopKind.CLOUDFLARE);
        assertThat(ClientIpResolver.classify("172.72.0.0")).isEqualTo(ClientIpResolver.HopKind.PUBLIC);
        // 104.16.0.0/13 e 104.24.0.0/14 são vizinhas: 104.16.0.0 a 104.27.255.255
        assertThat(ClientIpResolver.classify("104.15.255.255")).isEqualTo(ClientIpResolver.HopKind.PUBLIC);
        assertThat(ClientIpResolver.classify("104.16.0.0")).isEqualTo(ClientIpResolver.HopKind.CLOUDFLARE);
        assertThat(ClientIpResolver.classify("104.27.255.255")).isEqualTo(ClientIpResolver.HopKind.CLOUDFLARE);
        assertThat(ClientIpResolver.classify("104.28.0.0")).isEqualTo(ClientIpResolver.HopKind.PUBLIC);
        // 131.0.72.0/22: prefixo que corta o terceiro octeto
        assertThat(ClientIpResolver.classify("131.0.71.255")).isEqualTo(ClientIpResolver.HopKind.PUBLIC);
        assertThat(ClientIpResolver.classify("131.0.75.255")).isEqualTo(ClientIpResolver.HopKind.CLOUDFLARE);
        assertThat(ClientIpResolver.classify("131.0.76.0")).isEqualTo(ClientIpResolver.HopKind.PUBLIC);
        // 2a06:98c0::/29 = 2a06:98c0:: a 2a06:98c7:ffff:...
        assertThat(ClientIpResolver.classify("2a06:98bf:ffff::1")).isEqualTo(ClientIpResolver.HopKind.PUBLIC);
        assertThat(ClientIpResolver.classify("2a06:98c0::")).isEqualTo(ClientIpResolver.HopKind.CLOUDFLARE);
        assertThat(ClientIpResolver.classify("2a06:98c7:ffff:ffff::1")).isEqualTo(ClientIpResolver.HopKind.CLOUDFLARE);
        assertThat(ClientIpResolver.classify("2a06:98c8::")).isEqualTo(ClientIpResolver.HopKind.PUBLIC);
        // 172.16/12 continua interna, e não se confunde com a 172.64/13
        assertThat(ClientIpResolver.classify("172.16.0.3")).isEqualTo(ClientIpResolver.HopKind.INTERNAL);
        assertThat(ClientIpResolver.classify("proxy.interno")).isEqualTo(ClientIpResolver.HopKind.UNREADABLE);
        assertThat(ClientIpResolver.classify((String) null)).isEqualTo(ClientIpResolver.HopKind.UNREADABLE);
    }

    @Test
    @DisplayName("só UMA borda é pulada: cliente em faixa da Cloudflare (WARP) é o cliente")
    void onlyOneEdgeIsSkipped() {
        // WARP/Workers saem por endereço das faixas publicadas. Pulando toda
        // faixa da Cloudflare, o salto dele sumiria e valeria o 9.9.9.9 que
        // ele mesmo escreveu: um balde de login à escolha
        assertThat(resolve(PROXY, "9.9.9.9, 104.16.5.5, 172.71.10.5, 10.0.0.9")).isEqualTo("104.16.5.5");
        assertThat(resolve(PROXY, "104.16.5.5, 172.71.10.5, 10.0.0.9")).isEqualTo("104.16.5.5");
        // o forjado à esquerda não muda nada
        assertThat(resolve(PROXY, "1.1.1.1, 172.64.0.9, 104.16.5.5, 172.71.10.5, 10.0.0.9")).isEqualTo("104.16.5.5");
        // interno entre a borda e o cliente continua sendo pulado
        assertThat(resolve(PROXY, "9.9.9.9, 104.16.5.5, 10.0.0.2, 172.71.10.5, 10.0.0.9")).isEqualTo("104.16.5.5");
        assertThat(resolve(PROXY, "9.9.9.9, 2606:4700::5, 2400:cb00::1, 10.0.0.9")).isEqualTo(address("2606:4700::5"));
    }

    @Test
    @DisplayName("o número de bordas é configurável: duas, zero, e negativo não sobe")
    void configurableEdgeHops() {
        try {
            new ClientIpProxyConfig(List.of(), 2);
            assertThat(resolve(PROXY, "9.9.9.9, 203.0.113.7, 104.16.5.5, 172.71.10.5, 10.0.0.9"))
                    .isEqualTo("203.0.113.7");
            // a terceira já é cliente
            assertThat(resolve(PROXY, "9.9.9.9, 104.22.1.1, 104.16.5.5, 172.71.10.5, 10.0.0.9"))
                    .isEqualTo("104.22.1.1");
            // soquete em faixa da Cloudflare gasta uma das duas
            assertThat(resolve("172.71.10.5", "9.9.9.9, 104.22.1.1, 104.16.5.5")).isEqualTo("104.22.1.1");

            // zero: nenhuma borda é proxy, vale o primeiro não interno
            ClientIpResolver.configureTrustedEdgeHops(0);
            assertThat(resolve(PROXY, "203.0.113.7, 172.71.10.5, 10.0.0.9")).isEqualTo("172.71.10.5");

            assertThatThrownBy(() -> ClientIpResolver.configureTrustedEdgeHops(-1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(resolve(PROXY, "203.0.113.7, 172.71.10.5, 10.0.0.9")).isEqualTo("172.71.10.5");
        } finally {
            ClientIpResolver.configureTrustedEdgeHops(ClientIpResolver.DEFAULT_EDGE_HOPS);
        }
        assertThat(resolve(PROXY, "9.9.9.9, 104.16.5.5, 172.71.10.5, 10.0.0.9")).isEqualTo("104.16.5.5");
    }

    @Test
    @DisplayName("a lista de proxies é configurável; em branco vale a embutida; inválida não sobe")
    void configurableRanges() {
        try {
            // pelo mesmo caminho da propriedade economize.client-ip.trusted-proxies
            new ClientIpProxyConfig(List.of(" 198.51.100.0/24 ", "", "2001:db8:ff::/48", "192.0.2.7"), 1);

            assertThat(resolve(PROXY, "203.0.113.7, 198.51.100.200, 10.0.0.9")).isEqualTo("203.0.113.7");
            assertThat(resolve(PROXY, "203.0.113.7, 2001:db8:ff:1::1, 10.0.0.9")).isEqualTo("203.0.113.7");
            // sem barra é um endereço só
            assertThat(resolve(PROXY, "203.0.113.7, 192.0.2.7, 10.0.0.9")).isEqualTo("203.0.113.7");
            assertThat(resolve(PROXY, "203.0.113.7, 192.0.2.8, 10.0.0.9")).isEqualTo("192.0.2.8");
            // a lista SUBSTITUI a embutida: a Cloudflare deixa de ser proxy
            assertThat(resolve(PROXY, "203.0.113.7, 172.71.10.5, 10.0.0.9")).isEqualTo("172.71.10.5");

            // em branco não desliga a regra
            ClientIpResolver.configureTrustedProxies(List.of(" ", ""));
            assertThat(resolve(PROXY, "203.0.113.7, 172.71.10.5, 10.0.0.9")).isEqualTo("203.0.113.7");
            ClientIpResolver.configureTrustedProxies(null);
            assertThat(resolve(PROXY, "203.0.113.7, 172.71.10.5, 10.0.0.9")).isEqualTo("203.0.113.7");

            for (String invalid : List.of("cloudflare.com/20", "172.64.0.0/33", "172.64.0.0/-1", "172.64.0.0/x",
                    "2606:4700::/129", "999.1.1.1/8")) {
                assertThatThrownBy(() -> ClientIpResolver.configureTrustedProxies(List.of(invalid)))
                        .as(invalid).isInstanceOf(IllegalArgumentException.class);
            }
            // a tentativa inválida não deixou lista pela metade
            assertThat(resolve(PROXY, "203.0.113.7, 172.71.10.5, 10.0.0.9")).isEqualTo("203.0.113.7");
        } finally {
            ClientIpResolver.configureTrustedProxies(null);
        }
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
