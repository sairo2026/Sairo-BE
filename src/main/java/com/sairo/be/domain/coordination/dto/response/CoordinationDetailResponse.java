package com.sairo.be.domain.coordination.dto.response;

import com.sairo.be.domain.coordination.entity.CoordinationStatus;
import com.sairo.be.domain.coordination.entity.CustomerResponseResult;
import com.sairo.be.domain.coordination.entity.CustomerResponseRole;
import com.sairo.be.domain.property.entity.PropertyDealType;
import java.time.Instant;
import java.util.List;

public record CoordinationDetailResponse(
    Long coordinationId,
    PropertySummary property,
    CoordinationStatus status,
    Instant createdAt,
    Instant scheduledAt,
    Instant confirmedAt,
    List<CandidateTimeItem> candidateTimes,
    CustomerResponseItem tenantResponse,
    List<CustomerResponseItem> buyerResponses) {

  public record PropertySummary(
      Long propertyId,
      String address,
      String addressDetail,
      String propertyName,
      PropertyDealType dealType) {}

  public record CandidateTimeItem(Long candidateTimeId, Instant startsAt) {}

  public record CustomerResponseItem(
      Long responseId,
      CustomerResponseRole role,
      String name,
      String phone,
      CustomerResponseResult result,
      List<Long> offeredCandidateIds,
      List<Long> selectedCandidateIds,
      Instant submittedAt,
      String customerLinkUrl,
      Instant linkExpiresAt) {}
}
