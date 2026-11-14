package com.sairo.be.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class SignedTicketServiceIntegrationTest {

  @Autowired private SignedTicketService ticketService;

  private void assertError(Runnable action, ErrorCode expected) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
  }

  @Test
  void 발급한_티켓은_대상과_목적을_담아_검증된다() {
    String token =
        ticketService
            .issue(TicketType.EMAIL, "agent@example.com", "SIGNUP", 7L, Duration.ofMinutes(10))
            .token();

    VerifiedTicket verified = ticketService.verify(token, TicketType.EMAIL, "SIGNUP");

    assertThat(verified.subject()).isEqualTo("agent@example.com");
    assertThat(verified.purpose()).isEqualTo("SIGNUP");
    assertThat(verified.userId()).isEqualTo(7L);
    assertThat(verified.expiresAt()).isAfter(java.time.Instant.now());
  }

  @Test
  void 서명이_바뀌면_TOKEN_INVALID다() {
    String token =
        ticketService
            .issue(TicketType.EMAIL, "agent@example.com", "SIGNUP", null, Duration.ofMinutes(10))
            .token();
    String tampered = token.substring(0, token.length() - 2) + "xx";

    assertError(
        () -> ticketService.verify(tampered, TicketType.EMAIL, "SIGNUP"), ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 본문을_바꾸면_TOKEN_INVALID다() {
    String token =
        ticketService
            .issue(TicketType.EMAIL, "agent@example.com", "SIGNUP", null, Duration.ofMinutes(10))
            .token();
    String other =
        ticketService
            .issue(TicketType.EMAIL, "victim@example.com", "SIGNUP", null, Duration.ofMinutes(10))
            .token();
    String spliced = other.substring(0, other.indexOf('~')) + token.substring(token.indexOf('~'));

    assertError(
        () -> ticketService.verify(spliced, TicketType.EMAIL, "SIGNUP"), ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 종류와_목적이_다르면_TOKEN_INVALID다() {
    String token =
        ticketService
            .issue(TicketType.PHONE, "010-1234-5678", "SIGNUP", null, Duration.ofMinutes(10))
            .token();

    assertError(
        () -> ticketService.verify(token, TicketType.EMAIL, "SIGNUP"), ErrorCode.TOKEN_INVALID);
    assertError(
        () -> ticketService.verify(token, TicketType.PHONE, "CHANGE"), ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 만료된_티켓은_TOKEN_EXPIRED다() {
    String token =
        ticketService
            .issue(TicketType.PHONE, "010-1234-5678", "SIGNUP", null, Duration.ofSeconds(-1))
            .token();

    assertError(
        () -> ticketService.verify(token, TicketType.PHONE, "SIGNUP"), ErrorCode.TOKEN_EXPIRED);
  }

  @Test
  void 소진한_티켓은_다시_쓸_수_없다() {
    String token =
        ticketService
            .issue(TicketType.PHONE, "010-1234-5678", "SIGNUP", null, Duration.ofMinutes(10))
            .token();
    VerifiedTicket verified = ticketService.verify(token, TicketType.PHONE, "SIGNUP");

    ticketService.consume(verified);

    assertError(
        () -> ticketService.verify(token, TicketType.PHONE, "SIGNUP"), ErrorCode.TOKEN_INVALID);
    assertError(() -> ticketService.consume(verified), ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 형식이_아닌_문자열은_TOKEN_INVALID다() {
    assertError(
        () -> ticketService.verify("garbage", TicketType.EMAIL, null), ErrorCode.TOKEN_INVALID);
    assertError(() -> ticketService.verify("a~b", TicketType.EMAIL, null), ErrorCode.TOKEN_INVALID);
    assertError(() -> ticketService.verify(null, TicketType.EMAIL, null), ErrorCode.TOKEN_INVALID);
    assertError(() -> ticketService.verify("~", TicketType.EMAIL, null), ErrorCode.TOKEN_INVALID);
  }
}
