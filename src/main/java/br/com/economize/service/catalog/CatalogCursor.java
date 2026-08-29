package br.com.economize.service.catalog;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Cursor opaco da lista infinita.
 *
 * <p>
 * Offset puro não serve aqui: a ordenação tem componente de mercado que muda ao
 * longo do dia, e com offset qualquer reordenação entre duas páginas faz item
 * repetir ou sumir. O cursor carrega quatro coisas:
 *
 * <ul>
 * <li>{@code epoch} — a janela de ordenação em que a rolagem começou, para o
 * servidor reusar a MESMA ordem congelada nas páginas seguintes;</li>
 * <li>{@code lastId} — o último item entregue; é por ele que a página seguinte
 * é retomada, então mesmo que a ordem tenha sido recalculada (cache expirado)
 * a emenda continua exata;</li>
 * <li>{@code index} — posição do último item, usada só como plano B se o
 * lastId não existir mais na ordem;</li>
 * <li>{@code filterHash} — assinatura dos filtros estruturais; cursor de uma
 * consulta não pode ser reaproveitado em outra.</li>
 * </ul>
 *
 * <p>
 * O formato é opaco de propósito: o app trata como string e nada mais, o que
 * deixa o servidor livre para mudar a codificação depois sem quebrar o cliente.
 */
public record CatalogCursor(long epoch, int index, String lastId, String filterHash) {

    private static final String VERSION = "1";
    private static final String SEPARATOR = "|";
    private static final int FIELD_COUNT = 5;

    public String encode() {
        String raw = String.join(SEPARATOR, VERSION, Long.toString(epoch), Integer.toString(index),
                filterHash, lastId);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws IllegalArgumentException quando o cursor está corrompido ou veio
     *                                  de outra consulta — vira 400 ProblemDetail
     *                                  no handler global.
     */
    public static CatalogCursor decode(String encoded, String expectedFilterHash) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Cursor inválido: não é um cursor emitido por esta API.");
        }

        // limite no split para o id preservar qualquer separador que venha nele
        String[] parts = raw.split("\\" + SEPARATOR, FIELD_COUNT);
        if (parts.length != FIELD_COUNT || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("Cursor inválido: formato não reconhecido.");
        }

        long epoch;
        int index;
        try {
            epoch = Long.parseLong(parts[1]);
            index = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cursor inválido: posição corrompida.");
        }
        if (index < 0 || epoch < 0) {
            throw new IllegalArgumentException("Cursor inválido: posição corrompida.");
        }

        String filterHash = parts[3];
        if (!filterHash.equals(expectedFilterHash)) {
            throw new IllegalArgumentException(
                    "Cursor inválido: pertence a outra combinação de filtros. Recomece a lista sem cursor.");
        }

        return new CatalogCursor(epoch, index, parts[4], filterHash);
    }
}
