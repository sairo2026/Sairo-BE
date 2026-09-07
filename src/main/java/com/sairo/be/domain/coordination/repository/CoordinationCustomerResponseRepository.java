package com.sairo.be.domain.coordination.repository;

import com.sairo.be.domain.coordination.entity.CoordinationCustomerResponse;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoordinationCustomerResponseRepository
    extends JpaRepository<CoordinationCustomerResponse, Long> {}
