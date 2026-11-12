package br.com.economize.config;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * O IP de quem chamou, lido de um jeito que o próprio cliente não escolhe.
 *
 * <p><b>O que chega, medido em produção no Render.</b> O Render fica atrás da
 * Cloudflare, e o {@code X-Forwarded-For} vem como {@code <o que o cliente
 * mandou, se mandou>, <IP real do cliente>, <borda da Cloudflare>, <N saltos
 * internos 10.x>}, com N variável. Ou seja: a ponta ESQUERDA é texto do
 * cliente, e a ponta direita é a plataforma. Lendo da direita para a
 * esquerda: pulam-se os saltos internos, pula-se NO MÁXIMO UMA borda da
 * Cloudflare, e o salto seguinte é o cliente — foi a borda que o escreveu, ao
 * receber a conexão.
 *
 * <p><b>Por que a borda é proxy, e não cliente.</b> A primeira versão parava no
 * primeiro endereço público, e a borda da Cloudflare é pública (104.22.x,
 * 172.71.x...). O balde de tentativas de login ficava por borda, não por
 * cliente: a mesma pessoa caía em baldes diferentes a cada requisição, e
 * pessoas diferentes dividiam o mesmo. As faixas tratadas como proxy estão em
 * {@link #CLOUDFLARE_RANGES}.
 *
 * <p><b>Por que só uma borda.</b> Quem SAI para a internet por um endereço das
 * faixas da Cloudflare (WARP, Workers, alguns relays) tem cara de borda. Se
 * todo salto em faixa da Cloudflare fosse pulado, o salto desse cliente seria
 * pulado junto e valeria o que estivesse à esquerda — texto dele mesmo, ou
 * seja, um balde à escolha. Pulando só a borda que a plataforma escreveu, o
 * salto seguinte é o cliente esteja na faixa que estiver. O número de bordas
 * é configurável ({@code economize.client-ip.trusted-edge-hops}, padrão 1)
 * para o caso de a medição em produção mostrar duas; maior que o real, ele
 * devolve ao cliente em faixa da Cloudflare a escolha do balde.
 *
 * <p><b>Por que não "o primeiro da lista".</b> Era o que o login fazia, e servia
 * enquanto o valor só alimentava um aviso. Como chave de limite de tentativas
 * é um furo: basta inventar um cabeçalho por requisição para ganhar um balde
 * novo a cada uma. Aqui nada à esquerda do primeiro público é sequer lido.
 *
 * <p><b>Conexão direta pública ignora o cabeçalho.</b> Se quem abriu o soquete
 * tem endereço público que não é da Cloudflare, não há proxy nosso no caminho
 * e o cabeçalho inteiro é palavra do cliente. Vale o endereço do soquete. Um
 * soquete em faixa da Cloudflare é proxy, e a cadeia é lida — mas ele É a
 * borda, e conta como tal: o salto seguinte já é o cliente.
 *
 * <p>É um resolvedor só, para o limite de requisições e para o login, de
 * propósito: duas leituras diferentes do mesmo cabeçalho acabariam discordando
 * sobre quem é o cliente. Não usa {@code CF-Connecting-IP}/{@code
 * True-Client-IP} (qualquer um os escreve) nem
 * {@code server.forward-headers-strategy} (que confia na ponta esquerda).
 */
public final class ClientIpResolver {

    static final String HEADER = "X-Forwarded-For";

    /** O que um salto da cadeia (ou o soquete) é para o resolvedor. */
    public enum HopKind {
        /** Rede que não identifica cliente: 10/8, 192.168/16, loopback... */
        INTERNAL,
        /** Faixa de proxy confiável: as da Cloudflare, ou as da propriedade. */
        CLOUDFLARE,
        /** Qualquer outro endereço legível: é onde a leitura para. */
        PUBLIC,
        /** Não é um literal de IP. */
        UNREADABLE
    }

    /**
     * As faixas publicadas pela Cloudflare, IPv4 e IPv6.
     *
     * <p><b>De onde vem:</b> {@code https://www.cloudflare.com/ips-v4} e
     * {@code https://www.cloudflare.com/ips-v6}, conferidas em 11/11/2026.
     * <b>Precisa de revisão periódica:</b> a Cloudflare muda a lista de tempos
     * em tempos, e uma faixa nova que falte aqui volta a ser lida como cliente
     * (o defeito que esta lista conserta). Para trocar sem deploy, a
     * propriedade {@code economize.client-ip.trusted-proxies} substitui a
     * lista inteira — ver {@link ClientIpProxyConfig}. A rota
     * {@code GET /api/v1/users/me/connection} mostra como cada salto foi
     * classificado em produção.
     */
    static final List<String> CLOUDFLARE_RANGES = List.of(
            "173.245.48.0/20",
            "103.21.244.0/22",
            "103.22.200.0/22",
            "103.31.4.0/22",
            "141.101.64.0/18",
            "108.162.192.0/18",
            "190.93.240.0/20",
            "188.114.96.0/20",
            "197.234.240.0/22",
            "198.41.128.0/17",
            "162.158.0.0/15",
            "104.16.0.0/13",
            "104.24.0.0/14",
            "172.64.0.0/13",
            "131.0.72.0/22",
            "2400:cb00::/32",
            "2606:4700::/32",
            "2803:f800::/32",
            "2405:b500::/32",
            "2405:8100::/32",
            "2a06:98c0::/29",
            "2c0f:f248::/32");

    /**
     * Quantos saltos, a partir da direita, são examinados. A cadeia real tem
     * dois ou três internos; oito é folga larga, e o teto impede que um
     * cabeçalho de milhares de entradas vire trabalho por requisição.
     */
    static final int MAX_HOPS = 8;

    // Só LITERAIS de IP chegam ao InetAddress: com um nome, getByName faria
    // uma consulta DNS escolhida por quem mandou o cabeçalho
    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1?\\d?\\d)";
    private static final Pattern IPV4 = Pattern.compile("^" + OCTET + "(\\." + OCTET + "){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:]*:[0-9a-fA-F:.]*$");

    // Trocada uma vez, na subida, quando a propriedade vem preenchida
    private static volatile List<Cidr> trustedProxies = parseRanges(CLOUDFLARE_RANGES);

    /** Quantas bordas da Cloudflare a plataforma escreve antes do cliente. */
    static final int DEFAULT_EDGE_HOPS = 1;

    private static volatile int trustedEdgeHops = DEFAULT_EDGE_HOPS;

    private ClientIpResolver() {
    }

    /**
     * Quantos saltos em faixa de proxy confiável são pulados, no máximo,
     * antes de o salto seguinte valer como cliente. Zero volta à leitura
     * "primeiro público pela direita".
     *
     * @throws IllegalArgumentException se for negativo
     */
    static void configureTrustedEdgeHops(int hops) {
        if (hops < 0) {
            throw new IllegalArgumentException("Número de bordas confiáveis inválido: " + hops);
        }
        trustedEdgeHops = hops;
    }

    /**
     * Substitui a lista de proxies confiáveis. Lista vazia ou nula volta para
     * as faixas embutidas: "nenhum proxy" nunca é o que se quer atrás do
     * Render, e uma variável de ambiente em branco não pode desligar a regra.
     *
     * @throws IllegalArgumentException se alguma entrada não for um CIDR de
     *         literal de IP — a aplicação não sobe com lista pela metade
     */
    static void configureTrustedProxies(List<String> ranges) {
        List<String> cleaned = ranges == null ? List.of()
                : ranges.stream().map(String::trim).filter(range -> !range.isEmpty()).toList();
        trustedProxies = parseRanges(cleaned.isEmpty() ? CLOUDFLARE_RANGES : cleaned);
    }

    /** A classificação de um salto escrito no cabeçalho (ou de qualquer literal). */
    public static HopKind classify(String hop) {
        InetAddress address = hop == null ? null : parseLiteral(hop.trim());
        return address == null ? HopKind.UNREADABLE : classify(address);
    }

    /** A classificação de um endereço já resolvido, como o do soquete. */
    public static HopKind classify(InetAddress address) {
        if (isInternal(address)) return HopKind.INTERNAL;
        byte[] raw = address.getAddress();
        for (Cidr range : trustedProxies) {
            if (range.contains(raw)) return HopKind.CLOUDFLARE;
        }
        return HopKind.PUBLIC;
    }

    /** O IP do cliente, ou nulo quando não há nem soquete nem cadeia legível. */
    public static String resolve(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        return resolve(remote == null ? null : remote.getAddress(),
                request.getHeaders().getOrEmpty(HEADER));
    }

    static String resolve(InetAddress remote, List<String> forwardedHeaders) {
        String direct = remote == null ? null : remote.getHostAddress();
        HopKind socket = remote == null ? null : classify(remote);
        if (socket == HopKind.PUBLIC) return direct;
        // soquete em faixa da Cloudflare é a própria borda: gasta uma
        int edgesLeft = trustedEdgeHops - (socket == HopKind.CLOUDFLARE ? 1 : 0);

        // o mesmo cabeçalho pode vir em várias linhas; a ordem das linhas é a
        // ordem dos saltos, então juntá-las reconstrói a cadeia
        String[] hops = String.join(",", forwardedHeaders).split(",");
        int examined = 0;
        for (int i = hops.length - 1; i >= 0 && examined < MAX_HOPS; i--) {
            String hop = hops[i].trim();
            if (hop.isEmpty()) continue;
            examined++;
            InetAddress address = parseLiteral(hop);
            // salto ilegível no trecho que a plataforma escreve é cadeia
            // adulterada ou formato novo: não se adivinha, vale o soquete
            if (address == null) return direct;
            HopKind kind = classify(address);
            if (kind == HopKind.INTERNAL) continue;
            // só a borda que a plataforma escreveu é pulada; depois dela, o
            // salto é o cliente mesmo estando em faixa da Cloudflare (WARP)
            if (kind == HopKind.CLOUDFLARE && edgesLeft > 0) {
                edgesLeft--;
                continue;
            }
            return address.getHostAddress();
        }
        // só rede interna e borda (chamada de dentro da plataforma, ambiente local)
        return direct;
    }

    /**
     * Rede que não identifica cliente nenhum: 10/8, 172.16/12, 192.168/16,
     * 127/8, ::1, fc00::/7 e fe80::/10 (mais 169.254/16 e o endereço curinga,
     * pelo mesmo motivo).
     */
    static boolean isInternal(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        // fc00::/7 (endereço local único do IPv6): o Java não tem teste pronto
        return raw.length == 16 && (raw[0] & 0xfe) == 0xfc;
    }

    private static List<Cidr> parseRanges(List<String> ranges) {
        List<Cidr> parsed = new ArrayList<>(ranges.size());
        for (String range : ranges) parsed.add(Cidr.parse(range));
        return List.copyOf(parsed);
    }

    /** Uma faixa {@code endereço/prefixo}. IPv4 só casa com IPv4, IPv6 com IPv6. */
    private record Cidr(byte[] network, int prefix) {

        static Cidr parse(String text) {
            int slash = text.indexOf('/');
            // sem barra é um endereço só
            InetAddress address = parseLiteral(slash < 0 ? text : text.substring(0, slash));
            if (address == null) {
                throw new IllegalArgumentException("Faixa de proxy inválida: " + text);
            }
            byte[] raw = address.getAddress();
            int bits = raw.length * 8;
            int prefix = bits;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(text.substring(slash + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Faixa de proxy inválida: " + text);
                }
            }
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("Faixa de proxy inválida: " + text);
            }
            return new Cidr(raw, prefix);
        }

        boolean contains(byte[] candidate) {
            if (candidate.length != network.length) return false;
            int whole = prefix / 8;
            for (int i = 0; i < whole; i++) {
                if (candidate[i] != network[i]) return false;
            }
            int rest = prefix % 8;
            if (rest == 0) return true;
            int mask = (0xff << (8 - rest)) & 0xff;
            return (candidate[whole] & mask) == (network[whole] & mask);
        }
    }

    private static InetAddress parseLiteral(String hop) {
        String literal = hop;
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        if (!IPV4.matcher(literal).matches() && !IPV6.matcher(literal).matches()) return null;
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException | RuntimeException e) {
            return null;
        }
    }
}
