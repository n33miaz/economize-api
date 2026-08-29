package br.com.economize.dto.catalog;

import lombok.Data;

/**
 * Metadados de paginação. O app precisa deles para decidir sozinho quando
 * disparar a próxima página, sem nunca chegar a um "fim de lista" que ele não
 * previu.
 */
@Data
public class CatalogPageInfo {

    /** Limite efetivamente aplicado (pode ser menor que o pedido: há teto). */
    private int limit;

    /** Quantos itens vieram nesta página. */
    private int returned;

    /** Se ainda há itens depois desta página. */
    private boolean hasMore;

    /** Cursor da próxima página; nulo quando hasMore=false. */
    private String nextCursor;

    /** Total de itens do catálogo que casam com os filtros desta consulta. */
    private int totalMatched;

    /** Versão do catálogo estático — muda quando a lista de ativos muda. */
    private String catalogVersion;

    /**
     * Janela de ordenação a que esta página pertence. Páginas com o mesmo
     * rankEpoch são fatias da mesma ordem congelada; epoch diferente significa
     * que a ordem foi recalculada e a lista deveria ser reiniciada.
     */
    private long rankEpoch;

    /**
     * Quantas cotações novas ainda cabem na cota do dia. Serve para o app (e
     * para o suporte) entenderem por que um item veio UNQUOTED.
     */
    private int quoteBudgetRemaining;
}
