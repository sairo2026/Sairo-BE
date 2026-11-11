package com.sairo.be.domain.legal.service;

import com.sairo.be.domain.legal.entity.IdentityStatus;
import com.sairo.be.domain.legal.entity.PrivacyRequest;
import com.sairo.be.domain.legal.entity.PrivacyRequestEvent;
import com.sairo.be.domain.legal.entity.PrivacyRequestStatus;
import com.sairo.be.domain.legal.repository.PrivacyRequestEventRepository;
import com.sairo.be.domain.legal.repository.PrivacyRequestRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개인정보 권리 요청의 운영자 처리 단계다(OPS-07). 결정·실행·완료는 API 명세서 27.16절 표를 따르고 전달·협조요청·보완은 끝나지 않은 단계에서 받는다. 보완
 * 대기는 법정 기한을 멈추지 않는다.
 */
@Profile("!migrate")
@Service
public class PrivacyRequestProcessService {

  public enum Action {
    FORWARD,
    REQUEST_COOPERATION,
    REQUEST_SUPPLEMENT,
    APPROVE,
    RESTRICT,
    EXECUTE,
    REJECT,
    COMPLETE
  }

  public record Command(
      Action action, String note, String evidenceReference, IdentityStatus identityStatus) {}

  public record Result(long requestId, long eventId, PrivacyRequestStatus status, Instant at) {}

  private static final Set<PrivacyRequestStatus> DECIDABLE =
      EnumSet.of(PrivacyRequestStatus.RECEIVED, PrivacyRequestStatus.REVIEWING);

  private final PrivacyRequestRepository requests;
  private final PrivacyRequestEventRepository events;
  private final Clock clock;

  public PrivacyRequestProcessService(
      PrivacyRequestRepository requests, PrivacyRequestEventRepository events, Clock clock) {
    this.requests = requests;
    this.events = events;
    this.clock = clock;
  }

  /** 행위별로 받을 수 있는 현재 상태다. 표에 없는 상태에서 부르면 409 INVALID_TRANSITION이다. */
  public static Set<PrivacyRequestStatus> allowedFrom(Action action) {
    return switch (action) {
      case FORWARD ->
          EnumSet.of(
              PrivacyRequestStatus.RECEIVED,
              PrivacyRequestStatus.REVIEWING,
              PrivacyRequestStatus.WAITING_FOR_INFO);
      case REQUEST_COOPERATION ->
          EnumSet.of(
              PrivacyRequestStatus.RECEIVED,
              PrivacyRequestStatus.FORWARDED,
              PrivacyRequestStatus.WAITING_FOR_INFO,
              PrivacyRequestStatus.REVIEWING);
      case REQUEST_SUPPLEMENT ->
          EnumSet.of(
              PrivacyRequestStatus.RECEIVED,
              PrivacyRequestStatus.FORWARDED,
              PrivacyRequestStatus.REVIEWING);
      case APPROVE, RESTRICT, REJECT -> DECIDABLE;
      case EXECUTE ->
          EnumSet.of(PrivacyRequestStatus.APPROVED, PrivacyRequestStatus.PARTIALLY_RESTRICTED);
      case COMPLETE ->
          EnumSet.of(
              PrivacyRequestStatus.RECEIVED,
              PrivacyRequestStatus.REVIEWING,
              PrivacyRequestStatus.EXECUTING);
    };
  }

  public static PrivacyRequestStatus nextStatus(Action action) {
    return switch (action) {
      case FORWARD -> PrivacyRequestStatus.FORWARDED;
      case REQUEST_COOPERATION -> PrivacyRequestStatus.REVIEWING;
      case REQUEST_SUPPLEMENT -> PrivacyRequestStatus.WAITING_FOR_INFO;
      case APPROVE -> PrivacyRequestStatus.APPROVED;
      case RESTRICT -> PrivacyRequestStatus.PARTIALLY_RESTRICTED;
      case EXECUTE -> PrivacyRequestStatus.EXECUTING;
      case REJECT -> PrivacyRequestStatus.REJECTED;
      case COMPLETE -> PrivacyRequestStatus.COMPLETED;
    };
  }

  @Transactional
  public Result process(long requestId, long operatorId, Command command) {
    PrivacyRequest request =
        requests
            .findForUpdateById(requestId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    PrivacyRequestStatus previous = request.getStatus();
    Action action = command.action();
    if (!allowedFrom(action).contains(previous)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (command.identityStatus() != null) {
      request.recordIdentity(command.identityStatus());
    }
    if ((action == Action.APPROVE || action == Action.RESTRICT)
        && request.getIdentityStatus() != IdentityStatus.VERIFIED) {
      throw new BusinessException(ErrorCode.IDENTITY_NOT_VERIFIED);
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    PrivacyRequestStatus next = nextStatus(action);
    if (action == Action.APPROVE || action == Action.RESTRICT || action == Action.REJECT) {
      request.recordDecision(command.note());
    }
    if (action == Action.COMPLETE) {
      request.complete(command.evidenceReference(), now);
    }
    request.moveTo(next, operatorId);
    requests.save(request);
    PrivacyRequestEvent event =
        events.saveAndFlush(
            PrivacyRequestEvent.processed(
                request.getId(), action.name(), previous, next, detail(command), operatorId, now));
    return new Result(request.getId(), event.getId(), next, now);
  }

  private static String detail(Command command) {
    StringBuilder detail = new StringBuilder();
    detail.append(command.note() == null ? label(command.action()) : command.note());
    if (command.evidenceReference() != null) {
      detail.append(" (증빙: ").append(command.evidenceReference()).append(')');
    }
    if (command.identityStatus() != null) {
      detail.append(" (본인확인: ").append(command.identityStatus().name()).append(')');
    }
    return detail.toString();
  }

  private static String label(Action action) {
    return switch (action) {
      case FORWARD -> "처리 책임자에게 전달했다.";
      case REQUEST_COOPERATION -> "협조를 요청했다.";
      case REQUEST_SUPPLEMENT -> "보완을 요청했다.";
      case APPROVE -> "요청을 승인했다.";
      case RESTRICT -> "일부 제한을 결정했다.";
      case EXECUTE -> "실행을 시작했다.";
      case REJECT -> "요청을 거절했다.";
      case COMPLETE -> "처리를 완료했다.";
    };
  }
}
