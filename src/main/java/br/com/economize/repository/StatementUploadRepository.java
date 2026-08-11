package br.com.economize.repository;

import br.com.economize.model.StatementUpload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StatementUploadRepository extends JpaRepository<StatementUpload, UUID> {
    Optional<StatementUpload> findByUserIdAndFileHash(UUID userId, String fileHash);
}
