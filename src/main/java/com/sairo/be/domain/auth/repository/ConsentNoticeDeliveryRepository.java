package com.sairo.be.domain.auth.repository;

import com.sairo.be.domain.auth.entity.ConsentNoticeDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentNoticeDeliveryRepository
    extends JpaRepository<ConsentNoticeDelivery, Long> {}
