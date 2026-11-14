package com.sairo.be.domain.auth.entity;

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
@Table(name = "app_user_notification_preference")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationPreference {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel_key", nullable = false, length = 20)
  private NotificationChannel channel;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public static NotificationPreference create(
      Long userId, NotificationChannel channel, boolean enabled, Instant now) {
    NotificationPreference preference = new NotificationPreference();
    preference.userId = userId;
    preference.channel = channel;
    preference.enabled = channel.isMandatory() || enabled;
    preference.createdAt = now;
    preference.updatedAt = now;
    return preference;
  }

  public void change(boolean enabled, Instant now) {
    this.enabled = channel.isMandatory() || enabled;
    this.updatedAt = now;
  }
}
