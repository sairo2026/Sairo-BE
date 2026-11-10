package com.sairo.be.support.fixture;

import com.sairo.be.domain.auth.entity.AppUser;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class AppUserBuilder {

  private static final AtomicLong SEQUENCE = new AtomicLong(7_000_000L);
  private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

  private String kakaoProviderKey = String.valueOf(SEQUENCE.incrementAndGet());
  private String name = "테스트회원";
  private String email;
  private String rawPassword;
  private String phone;

  private AppUserBuilder() {}

  public static AppUserBuilder anAppUser() {
    return new AppUserBuilder();
  }

  public AppUserBuilder kakaoProviderKey(String kakaoProviderKey) {
    this.kakaoProviderKey = kakaoProviderKey;
    return this;
  }

  public AppUserBuilder name(String name) {
    this.name = name;
    return this;
  }

  public AppUserBuilder emailLogin(String email, String rawPassword) {
    this.email = email;
    this.rawPassword = rawPassword;
    return this;
  }

  public AppUserBuilder phone(String phone) {
    this.phone = phone;
    return this;
  }

  public AppUser build() {
    if (email != null) {
      Instant now = Instant.now();
      return AppUser.registerWithEmail(email, ENCODER.encode(rawPassword), name, phone, now, now);
    }
    return AppUser.registerWithKakao(kakaoProviderKey, name);
  }
}
