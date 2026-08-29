package br.com.economize.dto.catalog;

import lombok.Data;

import java.util.List;

/** Envelope da lista paginada: itens da página mais os metadados de rolagem. */
@Data
public class CatalogPage {

    private List<CatalogItem> items;

    private CatalogPageInfo page;

    public static CatalogPage of(List<CatalogItem> items, CatalogPageInfo page) {
        CatalogPage catalogPage = new CatalogPage();
        catalogPage.setItems(items);
        catalogPage.setPage(page);
        return catalogPage;
    }
}
