package br.com.economize.config;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * O IP de quem chamou, lido de um jeito que o próprio cliente não escolhe.
 *
 * <p><b>O que chega, medido em produção no Render.</b> O {@code X-Forwarded-For}
 * vem como {@code <o que o cliente mandou, se mandou>, <IP real do cliente>,
 * <N saltos internos 10.x>}, com N variável. Ou seja: a ponta ESQUERDA é texto
 * do cliente, e a ponta direita é a rede interna da plataforma. O endereço de
 * verdade é o primeiro PÚBLICO de quem lê da direita para a esquerda — foi a
 * plataforma que o escreveu, ao receber a conexão.
 *
 * <p><b>Por que não "o primeiro da lista".</b> Era o que o login fazia, e servia
 * enquanto o valor só alimentava um aviso. Como chave de limite de tentativas
 * é um furo: basta inventar um cabeçalho por requisição para ganhar um balde
 * novo a cada uma. Aqui nada à esquerda do primeiro público é sequer lido.
 *
 * <p><b>Conexão direta pública ignora o cabeçalho.</b> Se quem abriu o soquete
 * tem endereço público, não há proxy nosso no caminho e o cabeçalho inteiro é
 * palavra do cliente. Vale o endereço do soquete.
 *
 * <p>É um resolvedor só, para o limite de requisições e para o login, de
 * propósito: duas leituras diferentes do mesmo cabeçalho acabariam discordando
 * sobre quem é o cliente. Não usa {@code CF-Connecting-IP}/{@code
 * True-Client-IP} (qualquer um os escreve) nem
 * {@code server.forward-headers-strategy} (que confia na ponta esquerda).
 */
public final class ClientIpResolver {

    static final String HEADER = "X-Forwarded-For";

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

    private ClientIpResolver() {
    }

    /** O IP do cliente, ou nulo quando não há nem soquete nem cadeia legível. */
    public static String resolve(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        return resolve(remote == null ? null : remote.getAddress(),
                request.getHeaders().getOrEmpty(HEADER));
    }

    static String resolve(InetAddress remote, List<String> forwardedHeaders) {
        String direct = remote == null ? null : remote.getHostAddress();
        if (remote != null && !isInternal(remote)) return direct;

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
            if (!isInternal(address)) return address.getHostAddress();
        }
        // só rede interna (chamada de dentro da plataforma, ambiente local)
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
