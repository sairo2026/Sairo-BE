package com.sairo.be.domain.coordination.repository;

import com.sairo.be.domain.coordination.entity.CoordinationStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoordinationStatusHistoryRepository
    extends JpaRepository<CoordinationStatusHistory, Long> {}
