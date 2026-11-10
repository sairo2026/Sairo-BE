package com.sairo.be.domain.billing.repository;

import com.sairo.be.domain.billing.entity.ReturnGrant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnGrantRepository extends JpaRepository<ReturnGrant, Long> {

  List<ReturnGrant> findByOfficeIdAndRevokedAtIsNull(Long officeId);
}
