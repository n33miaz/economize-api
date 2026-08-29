package br.com.economize.service.catalog;

import br.com.economize.config.MarketCatalogProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Consulta já normalizada e validada. Existe para que o hash de filtros seja
 * calculado de um jeito só — é ele que impede o app de reaproveitar um cursor
 * de uma combinação de filtros em outra e receber uma página desalinhada.
 */
public record CatalogQuery(
        Set<String> types,
        Set<String> segments,
        String search,
        Sort sort,
        int limit,
        Set<String> favorites,
        String cursor,
        String filterHash) {

    public enum Sort {
        /** Tendência: sinal do usuário + relevância de mercado + variação recente. */
        TRENDING,
        /** Alfabética por nome — determinística e sem custo de cotação. */
        NAME,
        /** Alfabética por código. */
        CODE
    }

    private static final Set<String> VALID_TYPES = Set.of("currency", "crypto", "stock", "index");

    public static CatalogQuery of(String type, String segment, String q, String sort, Integer limit,
            String favorites, String cursor, MarketCatalogProperties properties) {

        Set<String> types = parseCsv(type).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        types.stream().filter(value -> !VALID_TYPES.contains(value)).findFirst().ifPresent(invalid -> {
            throw new IllegalArgumentException("Tipo desconhecido: " + invalid
                    + ". Aceitos: currency, crypto, stock, index.");
        });

        Set<String> validSegments = validSegments(properties);
        Set<String> segments = parseCsv(segment).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        segments.stream().filter(value -> !validSegments.contains(value)).findFirst().ifPresent(invalid -> {
            throw new IllegalArgumentException("Segmento desconhecido: " + invalid
                    + ". Aceitos: " + String.join(", ", validSegments) + ".");
        });

        Sort parsedSort = parseSort(sort);

        int effectiveLimit = properties.getDefaultPageSize();
        if (limit != null) {
            if (limit <= 0) {
                throw new IllegalArgumentException("O parâmetro limit deve ser maior que zero.");
            }
            // acima do teto reduz em vez de rejeitar: rolagem infinita não pode
            // quebrar por um número exagerado vindo do cliente
            effectiveLimit = Math.min(limit, properties.getMaxPageSize());
        }

        String search = (q == null || q.isBlank()) ? null : q.trim().toLowerCase(Locale.ROOT);

        Set<String> favoriteIds = parseCsv(favorites);

        String filterHash = hashOf(types, segments, search, parsedSort);

        return new CatalogQuery(Set.copyOf(types), Set.copyOf(segments), search, parsedSort,
                effectiveLimit, Set.copyOf(favoriteIds), cursor, filterHash);
    }

    /** Assinatura dos favoritos: entra na chave da ordem congelada, não no cursor. */
    public String favoritesSignature() {
        if (favorites.isEmpty()) {
            return "-";
        }
        return hash(favorites.stream().sorted().collect(Collectors.joining(",")));
    }

    private static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.TRENDING;
        }
        try {
            return Sort.valueOf(sort.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Ordenação desconhecida: " + sort
                    + ". Aceitas: trending, name, code.");
        }
    }

    private static Set<String> validSegments(MarketCatalogProperties properties) {
        Set<String> segments = properties.getAssets().stream()
                .map(MarketCatalogProperties.Asset::getSegment)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // moedas e cripto não estão no catálogo estático: vêm da descoberta
        segments.add("moedas");
        segments.add("cripto");
        return segments;
    }

    private static Set<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String hashOf(Set<String> types, Set<String> segments, String search, Sort sort) {
        String canonical = String.join("#",
                List.of(String.join(",", types.stream().sorted().toList()),
                        String.join(",", segments.stream().sorted().toList()),
                        search == null ? "" : search,
                        sort.name()));
        return hash(canonical);
    }

    private static String hash(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                hex.append(String.format("%02x", bytes[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 é obrigatório em qualquer JRE; se sumir, é ambiente quebrado
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }
}
