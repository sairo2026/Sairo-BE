package com.sairo.be.domain.auth.service;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {

  public static final int MIN_LENGTH = 8;
  public static final int MAX_LENGTH = 12;
  private static final String COMMON_PASSWORD_RESOURCE = "auth/common-passwords.list";

  private final Set<String> commonPasswords;

  public PasswordPolicy() {
    this.commonPasswords = loadCommonPasswords();
  }

  public void validateNewPassword(String field, String password) {
    if (password == null) {
      throw invalid(field, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    int length = password.codePointCount(0, password.length());
    if (length < MIN_LENGTH) {
      throw invalid(field, FieldErrorCode.TOO_SHORT, MIN_LENGTH + "자 이상 입력해야 합니다.");
    }
    if (length > MAX_LENGTH) {
      throw invalid(field, FieldErrorCode.TOO_LONG, MAX_LENGTH + "자 이하로 입력해야 합니다.");
    }
    if (isCommon(password)) {
      throw invalid(field, FieldErrorCode.NOT_ALLOWED, "흔하거나 유출된 적이 있는 비밀번호는 사용할 수 없습니다.");
    }
  }

  public boolean isCommon(String password) {
    return commonPasswords.contains(password.toLowerCase(Locale.ROOT));
  }

  private static BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }

  private static Set<String> loadCommonPasswords() {
    Set<String> loaded = new HashSet<>();
    try (InputStream stream = new ClassPathResource(COMMON_PASSWORD_RESOURCE).getInputStream();
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String entry = line.strip();
        if (!entry.isEmpty()) {
          loaded.add(entry.toLowerCase(Locale.ROOT));
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("흔한 비밀번호 목록을 읽지 못했습니다.", e);
    }
    return Set.copyOf(loaded);
  }
}
