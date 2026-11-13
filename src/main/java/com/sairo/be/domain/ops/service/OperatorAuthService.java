package com.sairo.be.domain.ops.service;

import com.sairo.be.domain.auth.service.KakaoAuthFlow;
import com.sairo.be.domain.auth.service.KakaoAuthorizationService;
import com.sairo.be.domain.ops.dto.response.OpsMeResponse;
import com.sairo.be.domain.ops.dto.response.OpsMfaVerifyResponse;
import com.sairo.be.domain.ops.dto.response.OpsStepUpResponse;
import com.sairo.be.domain.ops.entity.OperatorStatus;
import com.sairo.be.domain.ops.mapper.OpsAuthMapper;
import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorSessionManager;
import com.sairo.be.global.security.OperatorStepUpState;
import com.sairo.be.global.security.SessionKind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("!migrate")
@Service
public class OperatorAuthService {

  private static final int OAUTH_START_SESSION_SECONDS = 600;

  private final KakaoAuthorizationService authorizationService;
  private final OperatorAccountRepository accountRepository;
  private final OperatorMfaService mfaService;
  private final OperatorSessionManager sessionManager;
  private final OperatorSessionState sessionState;
  private final OperatorStepUpState stepUpState;
  private final Clock clock;
  private final String frontendBaseUrl;

  public OperatorAuthService(
      KakaoAuthorizationService authorizationService,
      OperatorAccountRepository accountRepository,
      OperatorMfaService mfaService,
      OperatorSessionManager sessionManager,
      OperatorSessionState sessionState,
      OperatorStepUpState stepUpState,
      Clock clock,
      @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
    this.authorizationService = authorizationService;
    this.accountRepository = accountRepository;
    this.mfaService = mfaService;
    this.sessionManager = sessionManager;
    this.sessionState = sessionState;
    this.stepUpState = stepUpState;
    this.clock = clock;
    this.frontendBaseUrl = frontendBaseUrl;
  }

  public void startLogin(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    HttpSession session = request.getSession(true);
    session.setMaxInactiveInterval(OAUTH_START_SESSION_SECONDS);
    response.sendRedirect(authorizationService.start(session, KakaoAuthFlow.OPERATOR_LOGIN));
  }

  public void completeKakaoLogin(
      HttpServletRequest request,
      HttpServletResponse response,
      String code,
      String state,
      String error)
      throws IOException {
    HttpSession session = request.getSession(false);
    Optional<String> providerKey =
        authorizationService.resolveProviderKey(
            session, KakaoAuthFlow.OPERATOR_LOGIN, code, state, error);
    if (providerKey.isEmpty()) {
      response.sendRedirect(frontendBaseUrl + "/ops/login?error=INVALID_REQUEST");
      return;
    }
    Optional<OperatorAccountRepository.Row> account =
        accountRepository.findByKakaoProviderKey(providerKey.get());
    if (account.isEmpty() || account.get().status() != OperatorStatus.ACTIVE) {
      session.invalidate();
      response.sendRedirect(frontendBaseUrl + "/ops/login?error=OPERATOR_NOT_ALLOWED");
      return;
    }
    HttpSession pending = sessionManager.beginPending(request);
    sessionState.markMfaPending(pending, account.get().id());
    response.sendRedirect(frontendBaseUrl + "/ops/mfa");
  }

  public OpsMfaVerifyResponse verifyMfa(
      HttpServletRequest request, HttpServletResponse response, String otpCode) {
    HttpSession session = request.getSession(false);
    long operatorId =
        sessionState
            .mfaPendingOperator(session)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    OperatorAccountRepository.Row account =
        accountRepository
            .findById(operatorId)
            .filter(found -> found.status() == OperatorStatus.ACTIVE)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    requireSuccess(mfaService.verify(operatorId, otpCode));
    OperatorPrincipal principal =
        new OperatorPrincipal(account.id(), account.name(), accountRepository.roles(account.id()));
    sessionManager.begin(request, response, principal);
    Instant now = clock.instant();
    return OpsAuthMapper.toVerifyResponse(
        principal,
        now.plus(SessionKind.OPERATOR.idleTimeout()),
        now.plus(SessionKind.OPERATOR.absoluteTimeout()));
  }

  public OpsStepUpResponse stepUp(
      OperatorPrincipal principal, HttpSession session, String otpCode) {
    requireSuccess(mfaService.verify(principal.operatorId(), otpCode));
    return new OpsStepUpResponse(
        com.sairo.be.global.support.TimeRules.toDisplayOffset(stepUpState.grant(session)));
  }

  public void logout(HttpServletRequest request) {
    sessionManager.end(request);
  }

  public OpsMeResponse me(OperatorPrincipal principal) {
    OperatorAccountRepository.Row account =
        accountRepository
            .findById(principal.operatorId())
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    return OpsAuthMapper.toMeResponse(
        account.id(), account.name(), accountRepository.roles(account.id()));
  }

  private void requireSuccess(OperatorMfaService.Outcome outcome) {
    switch (outcome.result()) {
      case SUCCESS -> {}
      case INVALID -> throw new BusinessException(ErrorCode.MFA_CODE_INVALID);
      case LOCKED ->
          throw BusinessException.withDetails(
              ErrorCode.MFA_LOCKED, Map.of("retryAfterSeconds", outcome.retryAfterSeconds()));
    }
  }
}
