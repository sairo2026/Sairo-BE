package com.sairo.be.domain.auth.service;

import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class PasswordHasher {

  private static final int BCRYPT_MAX_BYTES = 72;

  private final PasswordEncoder passwordEncoder;
  private final String dummyHash;

  public PasswordHasher(PasswordEncoder passwordEncoder) {
    this.passwordEncoder = passwordEncoder;
    this.dummyHash = passwordEncoder.encode("sairo-dummy-password");
  }

  public String hash(String rawPassword) {
    return passwordEncoder.encode(rawPassword);
  }

  public boolean matches(String rawPassword, String storedHash) {
    if (rawPassword == null || storedHash == null) {
      return false;
    }
    if (rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
      return false;
    }
    return passwordEncoder.matches(rawPassword, storedHash);
  }

  public void spendComparisonTime(String rawPassword) {
    matches(rawPassword, dummyHash);
  }
}
