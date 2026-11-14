package com.sairo.be.domain.visit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "visit_candidate_property")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitCandidateProperty {

  public static final int MAX_PER_COORDINATION = 20;
  public static final int DEFAULT_VISIT_MINUTES = 30;
  public static final int DEFAULT_TRAVEL_MINUTES = 15;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "coordination_id", nullable = false, updatable = false)
  private Long coordinationId;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "property_id", nullable = false, updatable = false)
  private Long propertyId;

  @Column(name = "property_office_id", nullable = false, updatable = false)
  private Long propertyOfficeId;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "visit_duration_minutes", nullable = false)
  private int visitDurationMinutes;

  @Column(name = "travel_duration_minutes", nullable = false)
  private int travelDurationMinutes;

  public static VisitCandidateProperty of(
      Long coordinationId, Long officeId, Long propertyId, Long propertyOfficeId, int sortOrder) {
    VisitCandidateProperty candidate = new VisitCandidateProperty();
    candidate.coordinationId = coordinationId;
    candidate.officeId = officeId;
    candidate.propertyId = propertyId;
    candidate.propertyOfficeId = propertyOfficeId;
    candidate.sortOrder = sortOrder;
    candidate.visitDurationMinutes = DEFAULT_VISIT_MINUTES;
    candidate.travelDurationMinutes = DEFAULT_TRAVEL_MINUTES;
    return candidate;
  }

  public boolean isCrossOffice() {
    return !officeId.equals(propertyOfficeId);
  }

  public void moveTo(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  public void arrange(int sortOrder, int visitDurationMinutes, int travelDurationMinutes) {
    this.sortOrder = sortOrder;
    this.visitDurationMinutes = visitDurationMinutes;
    this.travelDurationMinutes = travelDurationMinutes;
  }
}
