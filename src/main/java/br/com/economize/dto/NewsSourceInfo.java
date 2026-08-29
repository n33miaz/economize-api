package br.com.economize.dto;

/** Metadados de uma fonte de notícias disponível para o app configurar preferências. */
public record NewsSourceInfo(String id, String name, String region, String category) {
}
