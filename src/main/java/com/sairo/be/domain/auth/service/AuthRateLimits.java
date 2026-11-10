package com.sairo.be.domain.auth.service;

import com.sairo.be.global.support.RateLimitRule;
import java.time.Duration;

public final class AuthRateLimits {

  public static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);

  public static final RateLimitRule EMAIL_SEND_INTERVAL =
      RateLimitRule.of("auth-email-send-interval", 1, RESEND_INTERVAL);
  public static final RateLimitRule EMAIL_SEND_HOURLY =
      RateLimitRule.of("auth-email-send-hourly", 5, Duration.ofHours(1));
  public static final RateLimitRule EMAIL_VERIFY_IP =
      RateLimitRule.of("auth-email-verify-ip", 30, Duration.ofSeconds(60));

  public static final RateLimitRule PHONE_SEND_INTERVAL =
      RateLimitRule.of("auth-phone-send-interval", 1, RESEND_INTERVAL);
  public static final RateLimitRule PHONE_SEND_HOURLY =
      RateLimitRule.of("auth-phone-send-hourly", 5, Duration.ofHours(1));
  public static final RateLimitRule PHONE_SEND_IP_HOURLY =
      RateLimitRule.of("auth-phone-send-ip-hourly", 20, Duration.ofHours(1));
  public static final RateLimitRule PHONE_VERIFY_IP =
      RateLimitRule.of("auth-phone-verify-ip", 30, Duration.ofSeconds(60));

  public static final RateLimitRule LOGIN_FAILURE =
      RateLimitRule.of("auth-login-failure", 5, Duration.ofMinutes(15));
  public static final RateLimitRule LOGIN_LOCK =
      RateLimitRule.of("auth-login-lock", 1, Duration.ofMinutes(15));
  public static final RateLimitRule LOGIN_IP =
      RateLimitRule.of("auth-login-ip", 60, Duration.ofSeconds(60));

  public static final RateLimitRule PASSWORD_RESET_INTERVAL =
      RateLimitRule.of("auth-password-reset-interval", 1, RESEND_INTERVAL);
  public static final RateLimitRule PASSWORD_RESET_HOURLY =
      RateLimitRule.of("auth-password-reset-hourly", 5, Duration.ofHours(1));
  public static final RateLimitRule PASSWORD_RESET_IP =
      RateLimitRule.of("auth-password-reset-ip", 30, Duration.ofSeconds(60));

  public static final RateLimitRule UNSUBSCRIBE_IP =
      RateLimitRule.of("auth-unsubscribe-ip", 20, Duration.ofSeconds(60));
  public static final RateLimitRule RECOVERY_IP =
      RateLimitRule.of("auth-recovery-ip", 30, Duration.ofSeconds(60));
  public static final RateLimitRule SIGNUP_IP =
      RateLimitRule.of("auth-signup-ip", 30, Duration.ofSeconds(60));

  private AuthRateLimits() {}
}
