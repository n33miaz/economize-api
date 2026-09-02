package br.com.economize.repository;

import br.com.economize.model.FamilyGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface FamilyGroupRepository extends JpaRepository<FamilyGroup, UUID> {
    // Nenhuma consulta própria: a casa é sempre alcançada pelo MEMBRO do
    // usuário autenticado (FamilyMemberRepository.findByUserId), nunca por id
    // vindo da URL — não existe rota que receba id de grupo.
}
