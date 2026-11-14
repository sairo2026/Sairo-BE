package com.sairo.be.domain.home.entity;

import java.util.EnumSet;
import java.util.Set;

/** 알림함 알림 종류 18가지와 수신 설정 키, 이동 대상 종류의 대응이다(API 명세서 17장 NotificationListResponse). */
public enum NotificationType {
  OFFICE_REVIEW(null, EnumSet.of(NotificationTargetType.OFFICE_REGISTRATION)),
  MEMBERSHIP_APPROVAL_NEEDED(null, EnumSet.of(NotificationTargetType.MEMBERSHIP)),
  INVITATION(null, EnumSet.of(NotificationTargetType.MEMBERSHIP)),
  ASSIGNEE_CHANGED(
      null,
      EnumSet.of(
          NotificationTargetType.VISIT_COORDINATION,
          NotificationTargetType.PROPERTY,
          NotificationTargetType.CUSTOMER,
          NotificationTargetType.CONTRACT)),
  VISIT_SCHEDULED("VISIT_REMINDER", EnumSet.of(NotificationTargetType.VISIT_COORDINATION)),
  VISIT_CONFIRMED("VISIT_REMINDER", EnumSet.of(NotificationTargetType.VISIT_COORDINATION)),
  VISIT_RESCHEDULE_NEEDED("VISIT_REMINDER", EnumSet.of(NotificationTargetType.VISIT_COORDINATION)),
  VISIT_COMPLETION_PENDING("VISIT_REMINDER", EnumSet.of(NotificationTargetType.VISIT_COORDINATION)),
  VISIT_SUPERVISION_CHECK("VISIT_REMINDER", EnumSet.of(NotificationTargetType.VISIT_COORDINATION)),
  SECURITY_ALERT("SECURITY", EnumSet.of(NotificationTargetType.NONE)),
  NOTICE("MARKETING", EnumSet.of(NotificationTargetType.NONE)),
  TRIAL_ENDING(null, EnumSet.of(NotificationTargetType.SUBSCRIPTION)),
  PLAN_CHANGED(null, EnumSet.of(NotificationTargetType.SUBSCRIPTION)),
  PAYMENT(null, EnumSet.of(NotificationTargetType.SUBSCRIPTION)),
  RETURN_SCHEDULED(null, EnumSet.of(NotificationTargetType.DATA_RETURN)),
  DELETION_SCHEDULED(null, EnumSet.of(NotificationTargetType.DATA_RETURN)),
  SUPPORT_REPLY(null, EnumSet.of(NotificationTargetType.SUPPORT_TICKET)),
  TERMS_CHANGED(null, EnumSet.of(NotificationTargetType.NONE));

  private static final Set<NotificationType> EMAIL_DELIVERED =
      EnumSet.of(
          OFFICE_REVIEW,
          MEMBERSHIP_APPROVAL_NEEDED,
          TRIAL_ENDING,
          PLAN_CHANGED,
          PAYMENT,
          RETURN_SCHEDULED,
          DELETION_SCHEDULED,
          TERMS_CHANGED);

  private final String preferenceKey;
  private final Set<NotificationTargetType> targets;

  NotificationType(String preferenceKey, Set<NotificationTargetType> targets) {
    this.preferenceKey = preferenceKey;
    this.targets = targets;
  }

  /** 수신 설정 키다. 서비스 필수 알림은 null이며 설정과 무관하게 항상 남긴다. */
  public String preferenceKey() {
    return preferenceKey;
  }

  /** 알림함 외에 이메일로도 전달하는 서비스 고지 종류인지 돌려준다. */
  public boolean deliversByEmail() {
    return EMAIL_DELIVERED.contains(this);
  }

  public boolean allows(NotificationTargetType target) {
    return targets.contains(target);
  }
}
