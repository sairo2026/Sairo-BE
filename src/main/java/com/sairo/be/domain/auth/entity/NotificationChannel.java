package com.sairo.be.domain.auth.entity;

public enum NotificationChannel {
  VISIT_REMINDER(true),
  SECURITY(true),
  MARKETING(false);

  private final boolean defaultEnabled;

  NotificationChannel(boolean defaultEnabled) {
    this.defaultEnabled = defaultEnabled;
  }

  public boolean defaultEnabled() {
    return defaultEnabled;
  }

  public boolean isMandatory() {
    return this == SECURITY;
  }
}
