package com.sairo.be.domain.legal.repository;

import com.sairo.be.domain.legal.entity.PrivacyRequestEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrivacyRequestEventRepository extends JpaRepository<PrivacyRequestEvent, Long> {}
