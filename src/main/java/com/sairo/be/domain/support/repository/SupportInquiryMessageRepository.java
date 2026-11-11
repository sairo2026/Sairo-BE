package com.sairo.be.domain.support.repository;

import com.sairo.be.domain.support.entity.SupportInquiryMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportInquiryMessageRepository
    extends JpaRepository<SupportInquiryMessage, Long> {

  @Query(
      "select m from SupportInquiryMessage m where m.inquiryId = :inquiryId"
          + " and m.visibility = com.sairo.be.domain.support.entity.MessageVisibility.PUBLIC"
          + " order by m.createdAt asc, m.id asc")
  List<SupportInquiryMessage> findPublicByInquiry(@Param("inquiryId") Long inquiryId);

  @Query(
      "select m from SupportInquiryMessage m where m.id = :messageId and m.inquiryId = :inquiryId"
          + " and m.authorUserId = :userId"
          + " and m.visibility = com.sairo.be.domain.support.entity.MessageVisibility.PUBLIC")
  Optional<SupportInquiryMessage> findRequesterMessage(
      @Param("messageId") Long messageId,
      @Param("inquiryId") Long inquiryId,
      @Param("userId") Long userId);
}
