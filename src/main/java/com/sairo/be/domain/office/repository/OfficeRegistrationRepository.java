package com.sairo.be.domain.office.repository;

import com.sairo.be.domain.office.entity.OfficeRegistration;
import com.sairo.be.domain.office.entity.RegistrationStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OfficeRegistrationRepository extends JpaRepository<OfficeRegistration, Long> {

  Optional<OfficeRegistration> findFirstByApplicantUserIdOrderByIdDesc(Long applicantUserId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from OfficeRegistration r where r.id = :id")
  Optional<OfficeRegistration> findByIdForUpdate(@Param("id") Long id);

  Page<OfficeRegistration> findByStatusIn(
      Collection<RegistrationStatus> statuses, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from OfficeRegistration r where r.resultingOfficeId = :officeId")
  Optional<OfficeRegistration> findByResultingOfficeIdForUpdate(@Param("officeId") Long officeId);

  boolean existsByApplicantUserIdAndStatusIn(
      Long applicantUserId, Collection<RegistrationStatus> statuses);

  boolean existsByBusinessRegistrationNumberAndStatusIn(
      String businessRegistrationNumber, Collection<RegistrationStatus> statuses);
}
