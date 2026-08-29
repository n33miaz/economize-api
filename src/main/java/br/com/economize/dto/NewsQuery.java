package br.com.economize.dto;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filtros normalizados do endpoint de manchetes. Também é a chave do cache de
 * notícias: a normalização (trim/minúsculas/brancos viram null) garante que
 * requisições equivalentes compartilhem a mesma entrada.
 */
public record NewsQuery(String sources, String region, String category, String q, Integer limit) {

    /**
     * Valores de categoria do contrato antigo (estilo NewsAPI) que sempre foram
     * aceitos e ignorados. Continuam sem efeito: o APK publicado envia
     * "business" por padrão e não pode passar a receber resposta filtrada/vazia.
     */
    private static final Set<String> LEGACY_IGNORED_CATEGORIES = Set.of(
            "business", "entertainment", "general", "health", "science", "sports", "technology");

    public static NewsQuery of(String sources, String region, String category, String q, Integer limit) {
        String normalizedCategory = normalize(category);
        if (normalizedCategory != null && LEGACY_IGNORED_CATEGORIES.contains(normalizedCategory)) {
            normalizedCategory = null;
        }
        Integer normalizedLimit = (limit != null && limit > 0) ? limit : null;
        return new NewsQuery(normalize(sources), normalize(region), normalizedCategory,
                normalize(q), normalizedLimit);
    }

    /** IDs de fontes do filtro ?sources=, ou null quando não há filtro. */
    public Set<String> sourceIds() {
        if (sources == null) {
            return null;
        }
        return Arrays.stream(sources.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase();
    }
}
