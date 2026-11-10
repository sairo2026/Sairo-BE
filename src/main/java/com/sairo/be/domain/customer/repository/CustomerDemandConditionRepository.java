package com.sairo.be.domain.customer.repository;

import com.sairo.be.domain.customer.entity.CustomerDemandCondition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerDemandConditionRepository
    extends JpaRepository<CustomerDemandCondition, Long> {}
