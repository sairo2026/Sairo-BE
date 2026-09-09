package com.sairo.be.domain.auth.entity;

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
@Table(name = "app_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "kakao_provider_key", nullable = false, unique = true, length = 255)
  private String kakaoProviderKey;

  @Column(nullable = false, length = 50)
  private String name;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static AppUser register(String kakaoProviderKey, String name) {
    AppUser user = new AppUser();
    user.kakaoProviderKey = kakaoProviderKey;
    user.name = name;
    user.createdAt = Instant.now();
    return user;
  }
}
