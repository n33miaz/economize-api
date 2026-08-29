package br.com.economize.repository;

import br.com.economize.model.ConnectorAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectorAccountRepository extends JpaRepository<ConnectorAccount, UUID> {

    List<ConnectorAccount> findAllByUserIdOrderByNameAsc(UUID userId);

    // Dono validado NA CONSULTA, como em pluggy_items: conta de outro usuário
    // não retorna linha e a rota responde 404 — id alheio não pode nem confirmar
    // que existe (o IDOR do EC-037 nasceu de um findById solto)
    Optional<ConnectorAccount> findByIdAndUserId(UUID id, UUID userId);

    // Alvo do upsert de cada sincronização
    Optional<ConnectorAccount> findByUserIdAndProviderAccountId(UUID userId, String providerAccountId);

    // Origens ÓRFÃS do usuário: a conexão que as trouxe foi desvinculada e nada
    // novo entra por elas. São as únicas candidatas a serem readotadas quando o
    // usuário revincula a mesma instituição e o provedor devolve ids de conta
    // novos (ver ConnectorAccountService.register). A tabela tem unidades de
    // linhas por usuário, então a varredura é barata.
    List<ConnectorAccount> findAllByUserIdAndPluggyItemIdIsNull(UUID userId);

    /**
     * Solta as contas de uma conexão desvinculada. A coluna também é
     * {@code ON DELETE SET NULL} no schema (V16), mas o efeito é declarado aqui
     * para não depender do DDL: o H2 dos testes monta o schema pelo mapeamento
     * JPA, que não carrega a regra do FK, e uma correção que só existe em
     * produção é uma correção que ninguém testa.
     */
    @Modifying
    @Transactional
    @Query("update ConnectorAccount a set a.pluggyItemId = null "
            + "where a.user.id = :userId and a.pluggyItemId = :pluggyItemId")
    int detachFromItem(@Param("userId") UUID userId, @Param("pluggyItemId") UUID pluggyItemId);
}
