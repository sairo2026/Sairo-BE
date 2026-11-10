package com.sairo.be.domain.visit.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record PublicVisitLinkResponse(
    String role,
    String officeName,
    String managerDisplayName,
    String managerLegalRole,
    OffsetDateTime linkExpiresAt,
    String officeContactPhone,
    PrivacyNotice privacyNotice,
    CandidateWindow candidateWindow,
    List<Candidate> candidateProperties,
    List<TimeSlot> submittedTimeSlots,
    int responseVersion,
    String coordinationStatus) {

  public record PrivacyNotice(
      String purpose,
      List<String> collectedItems,
      String retentionPeriod,
      String noticeCode,
      String noticeVersion,
      int retentionDays) {}

  public record CandidateWindow(LocalDate startDate, LocalDate endDate) {}

  public record Candidate(Long candidatePropertyId, String displayName, String district) {}

  public record TimeSlot(OffsetDateTime startsAt, OffsetDateTime endsAt) {}
}
