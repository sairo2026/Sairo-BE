package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.entity.EmailVerificationPurpose;
import com.sairo.be.global.port.MailSender;
import com.sairo.be.global.port.MailSender.MailMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class AuthMailComposer {

  private final MailSender mailSender;
  private final String frontendBaseUrl;

  public AuthMailComposer(
      MailSender mailSender,
      @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
    this.mailSender = mailSender;
    this.frontendBaseUrl = frontendBaseUrl;
  }

  public void sendVerificationLink(String email, EmailVerificationPurpose purpose, String token) {
    String path =
        switch (purpose) {
          case SIGNUP -> "/signup/verify-email";
          case LINK -> "/settings/login-methods/verify-email";
          case CHANGE -> "/settings/contact/verify-email";
        };
    String link = frontendBaseUrl + path + "?token=" + token;
    mailSender.send(
        new MailMessage(
            email,
            "[사이로] 이메일 인증 링크",
            "아래 링크로 이메일 인증을 완료해 주세요. 링크는 30분 동안 한 번만 사용할 수 있습니다.\n" + link));
  }

  public void sendPasswordResetLink(String email, String token) {
    String link = frontendBaseUrl + "/password/reset?token=" + token;
    mailSender.send(
        new MailMessage(
            email,
            "[사이로] 비밀번호 재설정 링크",
            "아래 링크로 비밀번호를 다시 설정해 주세요. 링크는 30분 동안 한 번만 사용할 수 있습니다.\n" + link));
  }

  public void sendNotice(String email, String subject, String body) {
    mailSender.send(new MailMessage(email, subject, body));
  }
}
