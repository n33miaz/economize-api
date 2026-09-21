package br.com.economize.repository;

import br.com.economize.model.ShoppingItem;
import br.com.economize.model.ShoppingTrip;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Os itens do carrinho (V39).
 *
 * <p>Os itens de uma viagem saem sempre inteiros, lápides inclusive: a fusão
 * precisa das lápides para não ressuscitar o que outro aparelho apagou, e a
 * resposta as devolve para o aparelho que ainda não soube da remoção.
 */
@Repository
public interface ShoppingItemRepository extends JpaRepository<ShoppingItem, UUID> {

    List<ShoppingItem> findAllByTripIdOrderByCreatedAtAsc(UUID tripId);

    /** Os itens de VÁRIAS viagens numa ida só — a listagem monta o mapa por viagem em memória. */
    List<ShoppingItem> findAllByTripIdInOrderByCreatedAtAsc(Collection<UUID> tripIds);

    /**
     * O histórico de preços de um produto: as últimas vezes em que alguém da
     * casa (ou a própria pessoa) anotou um preço para este nome normalizado.
     *
     * <p>Lápide fica de fora, item sem preço fica de fora; item que a pessoa
     * viu e NÃO levou ({@code checked = false}) ENTRA — o preço foi observado,
     * e é isso que o histórico guarda. {@code allStores = true} ignora o
     * filtro de loja; com {@code false}, compara o nome da loja já em
     * minúsculas, que é como o serviço o entrega.
     */
    @Query("""
            select i as item, t as trip
            from ShoppingItem i
            join ShoppingTrip t on t.id = i.tripId
            where (t.userId = :userId or t.familyGroupId = :groupId)
              and i.normalizedName = :normalizedName
              and i.unitPrice is not null
              and i.deleted = false
              and (:allStores = true or lower(t.storeName) = :store)
            order by t.startedAt desc, i.updatedAt desc
            """)
    List<PriceObservation> findPriceHistory(@Param("userId") UUID userId,
                                            @Param("groupId") UUID groupId,
                                            @Param("normalizedName") String normalizedName,
                                            @Param("allStores") boolean allStores,
                                            @Param("store") String store,
                                            Pageable pageable);

    /** Um preço observado: o item e a viagem em que ele foi anotado. */
    interface PriceObservation {
        ShoppingItem getItem();

        ShoppingTrip getTrip();
    }
}
