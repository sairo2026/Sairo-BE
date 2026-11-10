package com.sairo.be.domain.property.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "entity_lifecycle_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EntityLifecycleEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "entity_type", nullable = false, length = 10, updatable = false)
  private LifecycleEntityType entityType;

  @Column(name = "property_id", updatable = false)
  private Long propertyId;

  @Column(name = "customer_id", updatable = false)
  private Long customerId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 18, updatable = false)
  private LifecycleEventType eventType;

  @Enumerated(EnumType.STRING)
  @Column(name = "creation_source", nullable = false, length = 10, updatable = false)
  private LifecycleCreationSource creationSource;

  @Column(name = "previous_category_id", updatable = false)
  private Long previousCategoryId;

  @Column(name = "new_category_id", updatable = false)
  private Long newCategoryId;

  @Column(name = "effective_at", nullable = false, updatable = false)
  private Instant effectiveAt;

  @Column(name = "recorded_at", nullable = false, updatable = false)
  private Instant recordedAt;

  @Column(name = "recorded_by_membership_id", updatable = false)
  private Long recordedByMembershipId;

  @Column(name = "correction_reason", columnDefinition = "text", updatable = false)
  private String correctionReason;

  @Column(name = "supersedes_event_id", updatable = false)
  private Long supersedesEventId;

  public static EntityLifecycleEvent propertyCreated(
      Long officeId, Long propertyId, Instant effectiveAt, Long recordedByMembershipId) {
    EntityLifecycleEvent event = new EntityLifecycleEvent();
    event.officeId = officeId;
    event.entityType = LifecycleEntityType.PROPERTY;
    event.propertyId = propertyId;
    event.eventType = LifecycleEventType.CREATED;
    event.creationSource = LifecycleCreationSource.MANUAL;
    event.effectiveAt = effectiveAt;
    event.recordedByMembershipId = recordedByMembershipId;
    return event;
  }

  public static EntityLifecycleEvent propertyArchived(
      Long officeId, Long propertyId, Instant effectiveAt, Long recordedByMembershipId) {
    return propertyEvent(
        LifecycleEventType.ARCHIVED, officeId, propertyId, effectiveAt, recordedByMembershipId);
  }

  public static EntityLifecycleEvent propertyRestored(
      Long officeId, Long propertyId, Instant effectiveAt, Long recordedByMembershipId) {
    return propertyEvent(
        LifecycleEventType.RESTORED, officeId, propertyId, effectiveAt, recordedByMembershipId);
  }

  public static EntityLifecycleEvent categoryChanged(
      Long officeId,
      Long propertyId,
      Long previousCategoryId,
      Long newCategoryId,
      Instant effectiveAt,
      Long recordedByMembershipId) {
    EntityLifecycleEvent event =
        propertyEvent(
            LifecycleEventType.CATEGORY_CHANGED,
            officeId,
            propertyId,
            effectiveAt,
            recordedByMembershipId);
    event.previousCategoryId = previousCategoryId;
    event.newCategoryId = newCategoryId;
    return event;
  }

  private static EntityLifecycleEvent propertyEvent(
      LifecycleEventType type,
      Long officeId,
      Long propertyId,
      Instant effectiveAt,
      Long recordedByMembershipId) {
    EntityLifecycleEvent event = new EntityLifecycleEvent();
    event.officeId = officeId;
    event.entityType = LifecycleEntityType.PROPERTY;
    event.propertyId = propertyId;
    event.eventType = type;
    event.creationSource = LifecycleCreationSource.MANUAL;
    event.effectiveAt = effectiveAt;
    event.recordedByMembershipId = recordedByMembershipId;
    return event;
  }

  @PrePersist
  void onCreate() {
    recordedAt = Instant.now();
  }
}
