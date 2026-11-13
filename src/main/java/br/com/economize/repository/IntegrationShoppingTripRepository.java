package br.com.economize.repository;

import br.com.economize.model.ShoppingTrip;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A leitura paginada das idas ao mercado para a integração (V44).
 *
 * <p>É um repositório à parte, só de leitura, para que a exportação do
 * histórico não acrescente nada ao {@link ShoppingTripRepository} do carrinho.
 * A regra de visibilidade é a mesma de lá, e na mesma forma: o par
 * {@code (userId, groupId)} na cláusula, com {@link ShoppingTripRepository#NO_GROUP}
 * para quem não tem casa — o que não é da pessoa não sai do banco.
 *
 * <p><b>Por que a ordem é {@code (createdAt, id)} crescente.</b> A paginação é
 * por cursor, para migrar o histórico inteiro sem pular nem repetir. Ordenar
 * pelo que não muda depois de gravado (a criação no servidor, desempatada pelo
 * id) faz o cursor continuar válido enquanto a pessoa segue comprando: compra
 * nova entra no FIM, e quem guardou o último cursor busca só o que chegou
 * depois. {@code startedAt}, que o carrinho usa para exibir, vem do aparelho e
 * pode ser corrigido.
 */
public interface IntegrationShoppingTripRepository extends Repository<ShoppingTrip, UUID> {

    @Query("""
            select t from ShoppingTrip t
            where (t.userId = :userId or t.familyGroupId = :groupId)
            order by t.createdAt asc, t.id asc
            """)
    List<ShoppingTrip> findFirstPage(@Param("userId") UUID userId,
                                     @Param("groupId") UUID groupId,
                                     Pageable pageable);

    @Query("""
            select t from ShoppingTrip t
            where (t.userId = :userId or t.familyGroupId = :groupId)
              and (t.createdAt > :createdAt or (t.createdAt = :createdAt and t.id > :id))
            order by t.createdAt asc, t.id asc
            """)
    List<ShoppingTrip> findPageAfter(@Param("userId") UUID userId,
                                     @Param("groupId") UUID groupId,
                                     @Param("createdAt") OffsetDateTime createdAt,
                                     @Param("id") UUID id,
                                     Pageable pageable);
}
