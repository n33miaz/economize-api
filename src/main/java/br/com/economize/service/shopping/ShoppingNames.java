package br.com.economize.service.shopping;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * O nome de um produto reduzido à chave do histórico de preços.
 *
 * <p>"Arroz Tio João 5kg", "arroz tio joao 5KG" e "ARROZ  TIO JOÃO 5kg" são o
 * mesmo produto anotado por pessoas diferentes em dias diferentes. Minúsculo,
 * sem acento e com espaços colapsados, os três caem na mesma chave — e
 * "quanto custou da última vez" sai de uma consulta por igualdade, que é a
 * única que o índice atende.
 *
 * <p>Deliberadamente NÃO tira número nem unidade: "5kg" e "1kg" são produtos
 * diferentes com preços diferentes, e fundi-los daria um histórico que mente.
 */
public final class ShoppingNames {

    /** O tamanho da coluna {@code normalized_name}. */
    static final int MAX_LENGTH = 120;

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private ShoppingNames() {
    }

    public static String normalize(String raw) {
        if (raw == null) return "";
        String lower = raw.toLowerCase(Locale.ROOT);
        // NFD separa a letra do acento; o acento vira uma marca combinante que
        // a classe \p{M} apaga — "ção" fica "cao"
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
        String stripped = MARKS.matcher(decomposed).replaceAll("");
        String collapsed = SPACES.matcher(stripped).replaceAll(" ").trim();
        if (collapsed.length() > MAX_LENGTH) {
            return collapsed.substring(0, MAX_LENGTH).trim();
        }
        return collapsed;
    }
}
