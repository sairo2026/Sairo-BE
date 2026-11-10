package com.sairo.be.domain.customer.repository;

import com.sairo.be.domain.customer.entity.CustomerMergeLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerMergeLogRepository extends JpaRepository<CustomerMergeLog, Long> {}
