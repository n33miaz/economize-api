package br.com.economize.dto;

import java.util.List;

/** Resposta do GET /api/v1/news/sources, no mesmo estilo de NewsResponse. */
public record NewsSourcesResponse(String status, List<NewsSourceInfo> sources) {
}
