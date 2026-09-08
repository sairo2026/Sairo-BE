package com.sairo.be.domain.coordination.mapper;

import com.sairo.be.domain.coordination.dto.response.CoordinationCreateResponse;
import com.sairo.be.domain.coordination.dto.response.PublicVisitResponse;
import com.sairo.be.domain.coordination.entity.Coordination;
import com.sairo.be.domain.coordination.entity.CoordinationCandidateTime;
import com.sairo.be.domain.coordination.entity.CoordinationCustomerResponse;
import com.sairo.be.domain.coordination.entity.CustomerResponseLink;
import com.sairo.be.domain.property.dto.response.PropertyDetailResponse;
import java.time.Instant;
import java.util.List;

public final class CoordinationMapper {

  private CoordinationMapper() {}

  public static CoordinationCreateResponse toCreateResponse(
      Coordination coordination,
      CoordinationCustomerResponse tenantResponse,
      CustomerResponseLink link,
      String customerLinkUrl) {
    return new CoordinationCreateResponse(
        coordination.getId(),
        coordination.getStatus(),
        tenantResponse.getId(),
        customerLinkUrl,
        link.getExpiresAt());
  }

  public static PublicVisitResponse toPublicVisitResponse(
      String officeName,
      PropertyDetailResponse property,
      CoordinationCustomerResponse response,
      List<CoordinationCandidateTime> offeredCandidates,
      List<Long> selectedCandidateIds,
      Instant scheduledAt,
      Instant expiresAt) {
    return new PublicVisitResponse(
        officeName,
        new PublicVisitResponse.PropertySummary(
            property.address(), property.propertyName(), property.dealType()),
        response.getRole(),
        response.getResult(),
        offeredCandidates.stream()
            .map(
                candidate ->
                    new PublicVisitResponse.CandidateTimeItem(
                        candidate.getId(), candidate.getStartsAt()))
            .toList(),
        selectedCandidateIds,
        scheduledAt,
        expiresAt);
  }
}
