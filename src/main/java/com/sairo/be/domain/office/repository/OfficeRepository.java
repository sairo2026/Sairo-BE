package com.sairo.be.domain.office.repository;

import com.sairo.be.domain.office.entity.Office;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfficeRepository extends JpaRepository<Office, Long> {

  Optional<Office> findByBusinessRegistrationNumber(String businessRegistrationNumber);
}
