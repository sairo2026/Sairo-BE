package com.sairo.be.domain.coordination.repository;

import com.sairo.be.domain.coordination.entity.Coordination;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoordinationRepository extends JpaRepository<Coordination, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Coordination c where c.id = :id")
  Optional<Coordination> findByIdForUpdate(@Param("id") Long id);
}
