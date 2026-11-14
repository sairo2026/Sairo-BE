package com.sairo.be.domain.visit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "visit_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitRequest {

  public static final int MEMBER_ACTIVE_LIMIT = 40;
  public static final int APPROVAL_ACTIVE_LIMIT = 10;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "condition_id", nullable = false, updatable = false)
  private Long conditionId;

  @Column(name = "approval_id", nullable = false, updatable = false)
  private Long approvalId;

  @Column(name = "property_id", nullable = false, updatable = false)
  private Long propertyId;

  @Column(name = "property_office_id", nullable = false, updatable = false)
  private Long propertyOfficeId;

  @Column(name = "access_grant_id", updatable = false)
  private Long accessGrantId;

  @Column(name = "applicant_office_id", nullable = false, updatable = false)
  private Long applicantOfficeId;

  @Column(name = "applicant_membership_id", nullable = false, updatable = false)
  private Long applicantMembershipId;

  @Column(name = "coordination_id", nullable = false, updatable = false)
  private Long coordinationId;

  @Column(name = "candidate_property_id", nullable = false, updatable = false)
  private Long candidatePropertyId;

  @Enumerated(EnumType.STRING)
  @Column(name = "request_kind", nullable = false, length = 10, updatable = false)
  private RequestKind requestKind;

  @Column(name = "replaces_schedule_item_id", updatable = false)
  private Long replacesScheduleItemId;

  @Column(name = "requested_start_at", nullable = false, updatable = false)
  private Instant requestedStartAt;

  @Column(name = "requested_end_at", nullable = false, updatable = false)
  private Instant requestedEndAt;

  @Column(name = "expected_duration_minutes", nullable = false, updatable = false)
  private int expectedDurationMinutes;

  @Column(name = "party_size", updatable = false)
  private Integer partySize;

  @Column(name = "request_note", columnDefinition = "text", updatable = false)
  private String requestNote;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private RequestStatus status;

  @Column(name = "status_reason", columnDefinition = "text")
  private String statusReason;

  @Column(name = "submitted_at", nullable = false, updatable = false)
  private Instant submittedAt;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private Long version = 1L;

  public static VisitRequest submitNew(
      Long conditionId,
      Long approvalId,
      Long propertyId,
      Long propertyOfficeId,
      Long accessGrantId,
      Long applicantOfficeId,
      Long applicantMembershipId,
      Long coordinationId,
      Long candidatePropertyId,
      Instant requestedStartAt,
      Instant requestedEndAt,
      int expectedDurationMinutes,
      Integer partySize,
      String requestNote,
      Instant now) {
    VisitRequest request = new VisitRequest();
    request.conditionId = conditionId;
    request.approvalId = approvalId;
    request.propertyId = propertyId;
    request.propertyOfficeId = propertyOfficeId;
    request.accessGrantId = accessGrantId;
    request.applicantOfficeId = applicantOfficeId;
    request.applicantMembershipId = applicantMembershipId;
    request.coordinationId = coordinationId;
    request.candidatePropertyId = candidatePropertyId;
    request.requestKind = RequestKind.NEW;
    request.requestedStartAt = requestedStartAt;
    request.requestedEndAt = requestedEndAt;
    request.expectedDurationMinutes = expectedDurationMinutes;
    request.partySize = partySize;
    request.requestNote = requestNote;
    request.status = RequestStatus.PENDING;
    request.submittedAt = now;
    request.updatedAt = now;
    return request;
  }

  public static VisitRequest submitChange(VisitRequest draft, Long replacesScheduleItemId) {
    draft.requestKind = RequestKind.CHANGE;
    draft.replacesScheduleItemId = replacesScheduleItemId;
    return draft;
  }

  public boolean isChange() {
    return requestKind == RequestKind.CHANGE;
  }

  public boolean isCrossOffice() {
    return !applicantOfficeId.equals(propertyOfficeId);
  }

  public VisitRequestStatusHistory openingHistory(Long actorMembershipId) {
    return VisitRequestStatusHistory.record(
        id,
        null,
        RequestStatus.PENDING,
        RequestStatusSource.BROKER,
        actorMembershipId,
        null,
        submittedAt);
  }

  public VisitRequestStatusHistory moveTo(
      RequestStatus next,
      RequestStatusSource source,
      Long actorMembershipId,
      String reason,
      Instant at) {
    if (!status.canMoveTo(next)) {
      throw new IllegalStateException("허용되지 않는 신청 상태 전이다: " + status + " -> " + next);
    }
    RequestStatus previous = status;
    status = next;
    statusReason = reason;
    updatedAt = at;
    if (next.isClosed()) {
      closedAt = at;
    }
    return VisitRequestStatusHistory.record(
        id, previous, next, source, actorMembershipId, reason, at);
  }
}
