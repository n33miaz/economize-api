package br.com.economize.repository;

import br.com.economize.model.FamilyInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FamilyInviteRepository extends JpaRepository<FamilyInvite, UUID> {

    // A busca do join: pelo hash do código, nunca pelo código. Devolve o
    // convite mesmo usado ou vencido — quem decide (e responde o MESMO 404
    // para os três casos) é o serviço, para a consulta não virar oráculo.
    Optional<FamilyInvite> findByCodeHash(String codeHash);

    // O ACEITE do convite, atômico: "uso único" da §7 valendo também sob
    // corrida. Ler, checar used_at e gravar são três passos — dois joins
    // simultâneos com o mesmo código leriam ambos used_at nulo e entrariam os
    // dois. Com a condição no próprio UPDATE, o segundo espera o lock da linha
    // e, ao reavaliar o WHERE, já encontra used_at preenchido: zero linhas, e
    // o serviço responde o mesmo 404 de convite inválido. A validade entra na
    // condição pelo mesmo motivo — o convite pode vencer entre a leitura e o
    // aceite.
    @Modifying
    @Transactional
    @Query("update FamilyInvite i set i.usedAt = :now "
            + "where i.id = :id and i.usedAt is null and i.expiresAt > :now")
    int claim(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    // O convite VIVO da casa, para o GET /family dizer que existe um e até
    // quando — sem o código, que só aparece na emissão
    Optional<FamilyInvite> findFirstByGroupIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            UUID groupId, OffsetDateTime now);

    List<FamilyInvite> findAllByGroupId(UUID groupId);

    // Um convite vivo por casa: emitir de novo apaga os não usados anteriores
    void deleteByGroupIdAndUsedAtIsNull(UUID groupId);

    // Purga oportunista, como em password_reset_tokens: sem job agendado no
    // projeto, é na emissão que os vencidos saem da tabela
    void deleteByExpiresAtBefore(OffsetDateTime cutoff);
}
