package com.sairo.be.domain.home.entity;

public enum NotificationTargetType {
  OFFICE_REGISTRATION,
  MEMBERSHIP,
  VISIT_COORDINATION,
  PROPERTY,
  CUSTOMER,
  CONTRACT,
  SUBSCRIPTION,
  DATA_RETURN,
  SUPPORT_TICKET,
  NONE;

  /** notification 표의 ck_notification_target과 같은 규칙이다. */
  public boolean requiresTargetId() {
    return this != SUBSCRIPTION && this != DATA_RETURN && this != NONE;
  }
}
