package com.sairo.be.e2e;

import com.sairo.be.global.port.MailSender.MailMessage;
import com.sairo.be.global.port.SmsSender.SmsMessage;
import com.sairo.be.global.port.stub.StubMailSender;
import com.sairo.be.global.port.stub.StubSmsSender;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Test-classpath-only delivery capture for a loopback-bound local E2E server. */
@Profile("local-e2e")
@RestController
@RequestMapping("/api/__e2e/messages")
@RequiresAccess(AccessPermission.PUBLIC)
public class E2EMessagesController {
  private final StubMailSender mail;
  private final StubSmsSender sms;
  private final byte[] expectedToken;

  E2EMessagesController(
      StubMailSender mail, StubSmsSender sms, @Value("${E2E_FIXTURE_TOKEN}") String fixtureToken) {
    if (fixtureToken.length() < 32) {
      throw new IllegalStateException("E2E fixture token must have at least 32 characters");
    }
    this.mail = mail;
    this.sms = sms;
    this.expectedToken = fixtureToken.getBytes(StandardCharsets.UTF_8);
  }

  @GetMapping("/mail/latest")
  ResponseEntity<MailMessage> latestMail(
      @RequestHeader("X-E2E-Token") String token, @RequestParam String to) {
    authenticate(token);
    List<MailMessage> sent = mail.sent();
    for (int index = sent.size() - 1; index >= 0; index--) {
      MailMessage message = sent.get(index);
      if (message.to().equalsIgnoreCase(to)) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(message);
      }
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  @GetMapping("/sms/latest")
  ResponseEntity<SmsMessage> latestSms(
      @RequestHeader("X-E2E-Token") String token, @RequestParam String toPhone) {
    authenticate(token);
    List<SmsMessage> sent = sms.sent();
    for (int index = sent.size() - 1; index >= 0; index--) {
      SmsMessage message = sent.get(index);
      if (message.toPhone().equals(toPhone)) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(message);
      }
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  private void authenticate(String token) {
    if (!MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }
}
