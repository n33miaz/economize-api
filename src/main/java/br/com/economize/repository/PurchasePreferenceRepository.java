package br.com.economize.repository;

import br.com.economize.model.PurchasePreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PurchasePreferenceRepository extends JpaRepository<PurchasePreference, UUID> {
    // a PK É o usuário: findById basta e não existe consulta por dono separada
}
