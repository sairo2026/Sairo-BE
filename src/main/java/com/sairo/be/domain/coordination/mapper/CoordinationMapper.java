package com.sairo.be.domain.coordination.mapper;

import com.sairo.be.domain.coordination.dto.response.CoordinationCreateResponse;
import com.sairo.be.domain.coordination.dto.response.CoordinationDetailResponse;
import com.sairo.be.domain.coordination.dto.response.CoordinationListResponse;
import com.sairo.be.domain.coordination.dto.response.PublicVisitResponse;
import com.sairo.be.domain.coordination.entity.Coordination;
import com.sairo.be.domain.coordination.entity.CoordinationCandidateTime;
import com.sairo.be.domain.coordination.entity.CoordinationCustomerResponse;
import com.sairo.be.domain.coordination.entity.CustomerResponseLink;
import com.sairo.be.domain.coordination.entity.CustomerResponseResult;
import com.sairo.be.domain.property.dto.response.PropertyDetailResponse;
import com.sairo.be.domain.property.dto.response.PropertyListResponse;
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

  public static CoordinationListResponse.CoordinationItem toListItem(
      Coordination coordination,
      PropertyListResponse.PropertyItem property,
      CoordinationCustomerResponse tenantResponse,
      List<CustomerResponseResult> buyerResults) {
    return new CoordinationListResponse.CoordinationItem(
        coordination.getId(),
        property.address(),
        tenantResponse.getCustomerName(),
        tenantResponse.getCustomerPhone(),
        coordination.getScheduledAt(),
        coordination.getStatus(),
        tenantResponse.getResult(),
        buyerResults);
  }

  public static CoordinationDetailResponse toDetailResponse(
      Coordination coordination,
      PropertyDetailResponse property,
      List<CoordinationCandidateTime> candidateTimes,
      CoordinationDetailResponse.CustomerResponseItem tenantResponse,
      List<CoordinationDetailResponse.CustomerResponseItem> buyerResponses) {
    return new CoordinationDetailResponse(
        coordination.getId(),
        new CoordinationDetailResponse.PropertySummary(
            property.propertyId(),
            property.address(),
            property.addressDetail(),
            property.propertyName(),
            property.dealType()),
        coordination.getStatus(),
        coordination.getCreatedAt(),
        coordination.getScheduledAt(),
        coordination.getConfirmedAt(),
        candidateTimes.stream()
            .map(
                candidate ->
                    new CoordinationDetailResponse.CandidateTimeItem(
                        candidate.getId(), candidate.getStartsAt()))
            .toList(),
        tenantResponse,
        buyerResponses);
  }

  public static CoordinationDetailResponse.CustomerResponseItem toCustomerResponseItem(
      CoordinationCustomerResponse response,
      CustomerResponseResult effectiveResult,
      List<Long> offeredCandidateIds,
      List<Long> selectedCandidateIds,
      String customerLinkUrl,
      Instant linkExpiresAt) {
    return new CoordinationDetailResponse.CustomerResponseItem(
        response.getId(),
        response.getRole(),
        response.getCustomerName(),
        response.getCustomerPhone(),
        effectiveResult,
        offeredCandidateIds,
        selectedCandidateIds,
        response.getSubmittedAt(),
        customerLinkUrl,
        linkExpiresAt);
  }
}
