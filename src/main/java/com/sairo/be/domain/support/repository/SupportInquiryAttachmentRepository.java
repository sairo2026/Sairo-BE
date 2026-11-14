package com.sairo.be.domain.support.repository;

import com.sairo.be.domain.support.entity.SupportInquiryAttachment;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportInquiryAttachmentRepository
    extends JpaRepository<SupportInquiryAttachment, Long> {

  String REQUESTER_VISIBLE =
      " a.inquiryId = :inquiryId and ("
          + "(a.uploadedByUserId is not null and a.messageId is null)"
          + " or (a.messageId in (select m.id from SupportInquiryMessage m"
          + " where m.inquiryId = :inquiryId"
          + " and m.visibility = com.sairo.be.domain.support.entity.MessageVisibility.PUBLIC)"
          + " and (a.uploadedByUserId is not null"
          + " or a.scanStatus = com.sairo.be.domain.support.entity.ScanStatus.CLEAN)))";

  @Query(
      "select a from SupportInquiryAttachment a where"
          + REQUESTER_VISIBLE
          + " order by a.createdAt asc, a.id asc")
  List<SupportInquiryAttachment> findVisibleToRequester(@Param("inquiryId") Long inquiryId);

  @Query(
      "select a from SupportInquiryAttachment a where a.id = :attachmentId and" + REQUESTER_VISIBLE)
  Optional<SupportInquiryAttachment> findVisibleToRequesterById(
      @Param("inquiryId") Long inquiryId, @Param("attachmentId") Long attachmentId);

  long countByInquiryIdAndUploadedByUserIdIsNotNull(Long inquiryId);

  long countByInquiryIdAndUploadedByOperatorIdIsNotNull(Long inquiryId);

  List<SupportInquiryAttachment> findByInquiryIdOrderByCreatedAtAscIdAsc(Long inquiryId);

  Optional<SupportInquiryAttachment> findByIdAndInquiryId(Long id, Long inquiryId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from SupportInquiryAttachment a where a.id = :id")
  Optional<SupportInquiryAttachment> findForUpdateById(@Param("id") Long id);

  @Query(
      "select a.id from SupportInquiryAttachment a where a.scanStatus ="
          + " com.sairo.be.domain.support.entity.ScanStatus.PENDING and a.createdAt < :before"
          + " order by a.id asc")
  List<Long> findPendingIdsCreatedBefore(@Param("before") Instant before, Pageable pageable);
}
