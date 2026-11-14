package com.sairo.be.domain.property.repository;

import com.sairo.be.domain.property.entity.EntityLifecycleEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntityLifecycleEventRepository extends JpaRepository<EntityLifecycleEvent, Long> {}
