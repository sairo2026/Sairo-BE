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
@Table(name = "app_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(length = 254)
  private String email;

  @Column(name = "password_hash", length = 255)
  private String passwordHash;

  @Column(name = "email_verified_at")
  private Instant emailVerifiedAt;

  @Column(name = "kakao_provider_key", length = 255)
  private String kakaoProviderKey;

  @Column(name = "kakao_reference_email", length = 254)
  private String kakaoReferenceEmail;

  @Column(length = 30)
  private String phone;

  @Column(name = "phone_verified_at")
  private Instant phoneVerifiedAt;

  @Column(nullable = false, length = 50)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "account_status", nullable = false, length = 10)
  private AccountStatus accountStatus;

  @Column(name = "failed_login_count", nullable = false)
  private int failedLoginCount;

  @Column(name = "failed_login_locked_until")
  private Instant failedLoginLockedUntil;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "withdrawn_at")
  private Instant withdrawnAt;

  @Column(name = "kakao_unlinked_at")
  private Instant kakaoUnlinkedAt;

  public static AppUser registerWithKakao(String kakaoProviderKey, String name) {
    AppUser user = new AppUser();
    user.kakaoProviderKey = kakaoProviderKey;
    user.name = name;
    user.accountStatus = AccountStatus.ACTIVE;
    user.failedLoginCount = 0;
    user.createdAt = Instant.now();
    return user;
  }

  public boolean isActive() {
    return accountStatus == AccountStatus.ACTIVE;
  }
}
