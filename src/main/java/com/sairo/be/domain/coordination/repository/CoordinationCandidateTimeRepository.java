package com.sairo.be.domain.coordination.repository;

import com.sairo.be.domain.coordination.entity.CoordinationCandidateTime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoordinationCandidateTimeRepository
    extends JpaRepository<CoordinationCandidateTime, Long> {}
