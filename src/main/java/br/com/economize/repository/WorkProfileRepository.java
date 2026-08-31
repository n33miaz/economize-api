package br.com.economize.repository;

import br.com.economize.model.WorkProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface WorkProfileRepository extends JpaRepository<WorkProfile, UUID> {
    // a PK É o usuário: findById basta e não existe consulta por dono separada
}
