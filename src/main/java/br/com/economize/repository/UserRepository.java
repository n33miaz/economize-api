package br.com.economize.repository;

import br.com.economize.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    /**
     * Só a versão da sessão, sem carregar a entidade: é a consulta que o filtro
     * de autenticação faz quando o cache não tem a resposta.
     */
    @Query("select u.sessionVersion from User u where u.email = :email")
    Optional<Integer> findSessionVersionByEmail(@Param("email") String email);

    /**
     * Soma 1 na versão da sessão no próprio banco. SQL nativo e atômico de
     * propósito: ler, somar e salvar a entidade perderia uma revogação
     * concorrente, e a coluna é {@code updatable = false} no mapeamento (ver
     * {@link User#getSessionVersion()}).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "update users set session_version = session_version + 1 where id = :id", nativeQuery = true)
    int incrementSessionVersion(@Param("id") UUID id);
}
