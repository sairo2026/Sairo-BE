package com.sairo.be.domain.support.repository;

import com.sairo.be.domain.support.entity.SupportInquiry;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportInquiryRepository extends JpaRepository<SupportInquiry, Long> {

  Optional<SupportInquiry> findByIdAndRequesterUserId(Long id, Long requesterUserId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from SupportInquiry i where i.id = :id and i.requesterUserId = :userId")
  Optional<SupportInquiry> findForUpdateByRequester(
      @Param("id") Long id, @Param("userId") Long userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from SupportInquiry i where i.id = :id")
  Optional<SupportInquiry> findForUpdateById(@Param("id") Long id);

  Page<SupportInquiry> findByRequesterUserId(Long requesterUserId, Pageable pageable);
}
