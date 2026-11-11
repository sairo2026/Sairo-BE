package com.sairo.be.domain.legal.mapper;

import com.sairo.be.domain.legal.dto.response.PrivacyRequestCreateResponse;
import com.sairo.be.domain.legal.dto.response.PrivacyRequestListItem;
import com.sairo.be.domain.legal.dto.response.PrivacyRequestResponse;
import com.sairo.be.domain.legal.entity.PrivacyRequest;
import com.sairo.be.domain.legal.repository.PrivacyRequestReadRepository.Row;
import com.sairo.be.global.support.TimeRules;

public final class PrivacyRequestMapper {

  private PrivacyRequestMapper() {}

  public static PrivacyRequestCreateResponse toCreate(PrivacyRequest request) {
    return new PrivacyRequestCreateResponse(
        request.getId(),
        request.getSupportInquiryId(),
        request.getRequestType().name(),
        request.getIdentityStatus().name(),
        request.getStatus().name(),
        TimeRules.toDisplayOffset(request.getReceivedAt()),
        TimeRules.toDisplayOffset(request.getLegalDueAt()),
        TimeRules.toDisplayOffset(request.getInternalDueAt()));
  }

  public static PrivacyRequestListItem toListItem(Row row) {
    return new PrivacyRequestListItem(
        row.requestId(),
        row.requestType(),
        row.status(),
        row.identityStatus(),
        TimeRules.toDisplayOffset(row.receivedAt()),
        TimeRules.toDisplayOffset(row.legalDueAt()),
        TimeRules.toDisplayOffset(row.completedAt()),
        row.decisionReason(),
        row.responsibleOfficeName());
  }

  public static PrivacyRequestResponse toDetail(Row row, String supplementRequest) {
    return new PrivacyRequestResponse(
        row.requestId(),
        row.requestType(),
        row.status(),
        row.identityStatus(),
        TimeRules.toDisplayOffset(row.receivedAt()),
        TimeRules.toDisplayOffset(row.legalDueAt()),
        TimeRules.toDisplayOffset(row.internalDueAt()),
        TimeRules.toDisplayOffset(row.completedAt()),
        row.decisionReason(),
        row.responsibleOfficeName(),
        row.handlerType(),
        supplementRequest);
  }
}
