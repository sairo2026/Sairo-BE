package com.sairo.be.domain.visit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "consent_guest_notice_ack")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsentGuestNoticeAck {

  public static final String LINK_KIND_APPROVER_PROPERTY = "APPROVER_PROPERTY";
  public static final String LINK_KIND_APPROVER_PORTAL = "APPROVER_PORTAL";
  public static final String ACTION_PORTAL_CLAIM = "PORTAL_CLAIM";
  public static final String ACTION_CONDITION_SAVE = "CONDITION_SAVE";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "link_kind", nullable = false, length = 20, updatable = false)
  private String linkKind;

  @Column(name = "approver_property_link_id", updatable = false)
  private Long approverPropertyLinkId;

  @Column(name = "approver_profile_id", updatable = false)
  private Long approverProfileId;

  @Column(name = "terms_code", nullable = false, length = 30, updatable = false)
  private String termsCode;

  @Column(name = "terms_version", nullable = false, length = 20, updatable = false)
  private String termsVersion;

  @Column(name = "action_type", nullable = false, length = 20, updatable = false)
  private String actionType;

  @Column(name = "resource_version", updatable = false)
  private Integer resourceVersion;

  @Column(name = "acknowledged_at", nullable = false, updatable = false)
  private Instant acknowledgedAt;

  public static ConsentGuestNoticeAck approverConditionSave(
      Long officeId,
      Long linkId,
      String termsCode,
      String termsVersion,
      int conditionVersion,
      Instant at) {
    ConsentGuestNoticeAck ack = new ConsentGuestNoticeAck();
    ack.officeId = officeId;
    ack.linkKind = LINK_KIND_APPROVER_PROPERTY;
    ack.approverPropertyLinkId = linkId;
    ack.termsCode = termsCode;
    ack.termsVersion = termsVersion;
    ack.actionType = ACTION_CONDITION_SAVE;
    ack.resourceVersion = conditionVersion;
    ack.acknowledgedAt = at;
    return ack;
  }

  /** 승인자 계정 세션으로 한 제출의 안내 확인이다. officeId는 그 제출이 다룬 출입승인을 가진 사무소다. */
  public static ConsentGuestNoticeAck portal(
      Long officeId,
      Long profileId,
      String termsCode,
      String termsVersion,
      String actionType,
      Integer resourceVersion,
      Instant at) {
    ConsentGuestNoticeAck ack = new ConsentGuestNoticeAck();
    ack.officeId = officeId;
    ack.linkKind = LINK_KIND_APPROVER_PORTAL;
    ack.approverProfileId = profileId;
    ack.termsCode = termsCode;
    ack.termsVersion = termsVersion;
    ack.actionType = actionType;
    ack.resourceVersion = resourceVersion;
    ack.acknowledgedAt = at;
    return ack;
  }
}
