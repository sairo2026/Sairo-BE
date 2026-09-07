package com.sairo.be.domain.coordination.repository;

import com.sairo.be.domain.coordination.entity.Coordination;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoordinationRepository extends JpaRepository<Coordination, Long> {}
