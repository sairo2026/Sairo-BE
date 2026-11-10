package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "구성원 한 명의 상세와 인계될 업무 건수")
public record MembershipDetailResponse(
    Long membershipId,
    Long officeId,
    Long userId,
    String displayName,
    String role,
    String status,
    String requestSource,
    String qualificationNumber,
    Boolean employmentReportConfirmed,
    Boolean trainingConfirmed,
    boolean billable,
    OffsetDateTime approvedAt,
    AssignedCounts assignedCounts,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {

  public record AssignedCounts(
      long customers, long properties, long tasks, long contracts, long confirmedVisits) {}
}
