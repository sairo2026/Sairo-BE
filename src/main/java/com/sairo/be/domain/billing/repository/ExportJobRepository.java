package com.sairo.be.domain.billing.repository;

import com.sairo.be.domain.billing.entity.ExportJob;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExportJobRepository extends JpaRepository<ExportJob, Long> {

  Optional<ExportJob> findByIdAndOfficeId(Long id, Long officeId);
}
