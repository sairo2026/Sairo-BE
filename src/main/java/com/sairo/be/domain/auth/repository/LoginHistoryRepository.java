package com.sairo.be.domain.auth.repository;

import com.sairo.be.domain.auth.entity.LoginHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, Long> {

  Page<LoginHistory> findByUserId(Long userId, Pageable pageable);
}
