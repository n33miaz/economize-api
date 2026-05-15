package br.com.painel_economico.repository;

import br.com.painel_economico.model.Report;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {
    Page<Report> findByUserIdAndPeriodOrderByStartDateDesc(UUID userId, Report.Period period, Pageable pageable);

    Page<Report> findByUserIdOrderByStartDateDesc(UUID userId, Pageable pageable);
}
