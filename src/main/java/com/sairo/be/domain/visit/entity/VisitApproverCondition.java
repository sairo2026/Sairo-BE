package com.sairo.be.domain.visit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "visit_approver_condition")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitApproverCondition {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "approval_id", nullable = false, updatable = false)
  private Long approvalId;

  @Column(name = "version_no", nullable = false, updatable = false)
  private int versionNo;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 12)
  private ConditionStatus status;

  @Column(name = "applies_from", nullable = false, updatable = false)
  private Instant appliesFrom;

  @Column(name = "applies_until", nullable = false, updatable = false)
  private Instant appliesUntil;

  @Column(name = "max_visits_per_day", updatable = false)
  private Integer maxVisitsPerDay;

  @Column(name = "min_gap_minutes", updatable = false)
  private Integer minGapMinutes;

  @Column(name = "min_lead_minutes", updatable = false)
  private Integer minLeadMinutes;

  @Column(name = "single_visit_max_minutes", updatable = false)
  private Integer singleVisitMaxMinutes;

  @Enumerated(EnumType.STRING)
  @Column(name = "grouping_preference", length = 12, updatable = false)
  private GroupingPreference groupingPreference;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10, updatable = false)
  private InputSource source;

  @Column(name = "recorded_by_membership_id", updatable = false)
  private Long recordedByMembershipId;

  @Column(name = "registered_at", nullable = false, updatable = false)
  private Instant registeredAt;

  @Column(name = "superseded_at")
  private Instant supersededAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "approval_role", nullable = false, length = 12, updatable = false)
  private ApprovalRole approvalRole;

  @Column(name = "entered_by_profile_id", updatable = false)
  private Long enteredByProfileId;

  public static VisitApproverCondition register(
      Long approvalId,
      int versionNo,
      Instant appliesFrom,
      Instant appliesUntil,
      Integer maxVisitsPerDay,
      Integer minGapMinutes,
      Integer minLeadMinutes,
      Integer singleVisitMaxMinutes,
      GroupingPreference groupingPreference,
      InputSource source,
      Long recordedByMembershipId,
      Instant registeredAt) {
    VisitApproverCondition condition = new VisitApproverCondition();
    condition.approvalId = approvalId;
    condition.versionNo = versionNo;
    condition.status = ConditionStatus.ACTIVE;
    condition.appliesFrom = appliesFrom;
    condition.appliesUntil = appliesUntil;
    condition.maxVisitsPerDay = maxVisitsPerDay;
    condition.minGapMinutes = minGapMinutes;
    condition.minLeadMinutes = minLeadMinutes;
    condition.singleVisitMaxMinutes = singleVisitMaxMinutes;
    condition.groupingPreference = groupingPreference;
    condition.source = source;
    condition.recordedByMembershipId = recordedByMembershipId;
    condition.registeredAt = registeredAt;
    condition.approvalRole = ApprovalRole.PRIMARY;
    return condition;
  }

  public void supersede(Instant at) {
    status = ConditionStatus.SUPERSEDED;
    supersededAt = at;
  }

  public boolean isActive() {
    return status == ConditionStatus.ACTIVE;
  }
}
