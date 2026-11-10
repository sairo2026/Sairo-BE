package com.sairo.be.domain.customer.repository;

import com.sairo.be.domain.customer.entity.CustomerConsultationRevision;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerConsultationRevisionRepository
    extends JpaRepository<CustomerConsultationRevision, Long> {

  List<CustomerConsultationRevision> findByConsultationIdOrderById(Long consultationId);
}
