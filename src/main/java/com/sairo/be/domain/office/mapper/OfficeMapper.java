package com.sairo.be.domain.office.mapper;

import com.sairo.be.domain.office.dto.response.OfficeChangeRequestItemResponse;
import com.sairo.be.domain.office.dto.response.OfficeChangeRequestResponse;
import com.sairo.be.domain.office.dto.response.OfficeDetailResponse;
import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.entity.OfficeChangeRequest;
import com.sairo.be.global.support.TimeRules;
import java.util.Map;

public final class OfficeMapper {

  private OfficeMapper() {}

  public static OfficeDetailResponse toDetail(Office office, boolean includePhone) {
    return new OfficeDetailResponse(
        office.getId(),
        office.getName(),
        office.getEntityType().name(),
        office.getRegistrationAuthority(),
        office.getRealEstateLicenseNumber(),
        office.getBusinessRegistrationNumber(),
        office.getRepresentativeName(),
        includePhone ? office.getRepresentativePhone() : null,
        office.getAddress(),
        TimeRules.toDisplayOffset(office.getCreatedAt()),
        TimeRules.toDisplayOffset(office.getUpdatedAt()),
        office.getVersion());
  }

  public static OfficeChangeRequestResponse toChangeResponse(
      OfficeChangeRequest request, Map<String, String> values, Office office) {
    return new OfficeChangeRequestResponse(
        request.getId(),
        request.getOfficeId(),
        request.getChangeType().name(),
        values,
        request.getStatus().name(),
        TimeRules.toDisplayOffset(request.getCreatedAt()),
        TimeRules.toDisplayOffset(office.getUpdatedAt()),
        office.getVersion());
  }

  public static OfficeChangeRequestItemResponse toChangeItem(
      OfficeChangeRequest request, Map<String, String> values) {
    return new OfficeChangeRequestItemResponse(
        request.getId(),
        request.getChangeType().name(),
        values,
        request.getStatus().name(),
        request.getRejectionReason(),
        TimeRules.toDisplayOffset(request.getCreatedAt()));
  }
}
