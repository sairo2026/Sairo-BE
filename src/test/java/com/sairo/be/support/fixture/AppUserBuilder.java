package com.sairo.be.support.fixture;

import com.sairo.be.domain.auth.entity.AppUser;
import java.util.concurrent.atomic.AtomicLong;

public final class AppUserBuilder {

  private static final AtomicLong SEQUENCE = new AtomicLong(7_000_000L);

  private String kakaoProviderKey = String.valueOf(SEQUENCE.incrementAndGet());
  private String name = "테스트회원";

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

  public AppUser build() {
    return AppUser.registerWithKakao(kakaoProviderKey, name);
  }
}
