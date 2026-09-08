package com.sairo.be.domain.coordination.dto.response;

import com.sairo.be.domain.coordination.entity.CustomerResponseResult;
import com.sairo.be.domain.coordination.entity.CustomerResponseRole;
import com.sairo.be.domain.property.entity.PropertyDealType;
import java.time.Instant;
import java.util.List;

public record PublicVisitResponse(
    String officeName,
    PropertySummary propertySummary,
    CustomerResponseRole role,
    CustomerResponseResult result,
    List<CandidateTimeItem> candidateTimes,
    List<Long> selectedCandidateIds,
    Instant scheduledAt,
    Instant expiresAt) {

  public record PropertySummary(String address, String propertyName, PropertyDealType dealType) {}

  public record CandidateTimeItem(Long candidateTimeId, Instant startsAt) {}
}
