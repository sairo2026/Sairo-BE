package com.sairo.be.domain.ops.service;

import com.sairo.be.domain.auth.service.KakaoAuthFlow;
import com.sairo.be.domain.auth.service.KakaoAuthorizationService;
import com.sairo.be.domain.ops.dto.response.OperatorInvitationAcceptResponse;
import com.sairo.be.domain.ops.dto.response.OperatorInvitationKakaoStartResponse;
import com.sairo.be.domain.ops.dto.response.OperatorInvitationPreviewResponse;
import com.sairo.be.domain.ops.dto.response.OperatorMfaEnrollmentResponse;
import com.sairo.be.domain.ops.entity.InvitationStatus;
import com.sairo.be.domain.ops.mapper.OpsAuthMapper;
import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OperatorInvitationRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.OperatorSessionManager;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.global.support.TextCipher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class OperatorInvitationAuthService {

  private static final int OAUTH_START_SESSION_SECONDS = 600;

  private final OperatorInvitationRepository invitationRepository;
  private final OperatorAccountRepository accountRepository;
  private final OpsAuditLogRepository auditLogRepository;
  private final KakaoAuthorizationService authorizationService;
  private final OperatorSessionManager sessionManager;
  private final OperatorSessionState sessionState;
  private final TotpService totpService;
  private final TextCipher textCipher;
  private final RequestRateLimiter rateLimiter;
  private final Clock clock;
  private final String frontendBaseUrl;

  public OperatorInvitationAuthService(
      OperatorInvitationRepository invitationRepository,
      OperatorAccountRepository accountRepository,
      OpsAuditLogRepository auditLogRepository,
      KakaoAuthorizationService authorizationService,
      OperatorSessionManager sessionManager,
      OperatorSessionState sessionState,
      TotpService totpService,
      TextCipher textCipher,
      RequestRateLimiter rateLimiter,
      Clock clock,
      @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
    this.invitationRepository = invitationRepository;
    this.accountRepository = accountRepository;
    this.auditLogRepository = auditLogRepository;
    this.authorizationService = authorizationService;
    this.sessionManager = sessionManager;
    this.sessionState = sessionState;
    this.totpService = totpService;
    this.textCipher = textCipher;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.frontendBaseUrl = frontendBaseUrl;
  }

  @Transactional(readOnly = true)
  public OperatorInvitationPreviewResponse preview(String token, String ipAddress) {
    rateLimiter.hit(OpsRateLimits.PUBLIC_IP, ipAddress);
    OperatorInvitationRepository.Row invitation = usableByToken(token);
    return OpsAuthMapper.toPreviewResponse(invitation, invitationRepository.roles(invitation.id()));
  }

  @Transactional(readOnly = true)
  public OperatorInvitationKakaoStartResponse startKakao(
      String token, String ipAddress, HttpServletRequest request) {
    rateLimiter.hit(OpsRateLimits.PUBLIC_IP, ipAddress);
    OperatorInvitationRepository.Row invitation = usableByToken(token);
    HttpSession session = request.getSession(true);
    session.setMaxInactiveInterval(OAUTH_START_SESSION_SECONDS);
    sessionState.markStartInvitation(session, invitation.id());
    return new OperatorInvitationKakaoStartResponse(
        authorizationService.start(session, KakaoAuthFlow.OPERATOR_INVITATION));
  }

  @Transactional
  public void completeKakao(
      HttpServletRequest request,
      HttpServletResponse response,
      String ipAddress,
      String code,
      String state,
      String error)
      throws IOException {
    rateLimiter.hit(OpsRateLimits.PUBLIC_IP, ipAddress);
    HttpSession session = request.getSession(false);
    Optional<Long> invitationId = sessionState.startInvitation(session);
    Optional<String> providerKey =
        authorizationService.resolveProviderKey(
            session, KakaoAuthFlow.OPERATOR_INVITATION, code, state, error);
    if (invitationId.isEmpty() || providerKey.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
    OperatorInvitationRepository.Row invitation =
        invitationRepository
            .lockById(invitationId.get())
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
    requireUsable(invitation);
    if (accountRepository.existsByKakaoProviderKey(providerKey.get())) {
      throw new BusinessException(ErrorCode.OPERATOR_ALREADY_EXISTS);
    }
    Instant now = clock.instant();
    HttpSession enrollment = sessionManager.beginPending(request);
    Instant until = sessionState.markEnrollment(enrollment, invitation.id());
    invitationRepository.bindKakao(invitation.id(), providerKey.get(), until, now);
    response.sendRedirect(frontendBaseUrl + "/ops/invitations/enroll");
  }

  @Transactional
  public OperatorMfaEnrollmentResponse enrollMfa(HttpSession session, String ipAddress) {
    rateLimiter.hit(OpsRateLimits.PUBLIC_IP, ipAddress);
    OperatorInvitationRepository.Row invitation = enrolling(session);
    String secret = totpService.newSecret();
    Instant now = clock.instant();
    invitationRepository.savePendingSecret(invitation.id(), textCipher.encrypt(secret), now);
    return OpsAuthMapper.toEnrollmentResponse(
        totpService.otpauthUri(secret), secret, invitation.enrollmentExpiresAt());
  }

  @Transactional
  public OperatorInvitationAcceptResponse accept(
      HttpServletRequest request, HttpServletResponse response, String otpCode) {
    HttpSession session = request.getSession(false);
    OperatorInvitationRepository.Row invitation = enrolling(session);
    rateLimiter.hit(OpsRateLimits.INVITATION_ACCEPT, "invitation:" + invitation.id());
    if (invitation.pendingTotpSecretEncrypted() == null) {
      throw new BusinessException(ErrorCode.MFA_CODE_INVALID);
    }
    String secret = textCipher.decrypt(invitation.pendingTotpSecretEncrypted());
    Instant now = clock.instant();
    if (!totpService.verify(secret, otpCode, now)) {
      throw new BusinessException(ErrorCode.MFA_CODE_INVALID);
    }
    if (accountRepository.existsByKakaoProviderKey(invitation.kakaoProviderKey())) {
      throw new BusinessException(ErrorCode.OPERATOR_ALREADY_EXISTS);
    }
    long operatorId;
    try {
      operatorId =
          accountRepository.insert(
              invitation.kakaoProviderKey(),
              invitation.inviteeName(),
              invitation.pendingTotpSecretEncrypted());
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.OPERATOR_ALREADY_EXISTS);
    }
    List<OperatorRole> roles = invitationRepository.roles(invitation.id());
    roles.forEach(role -> accountRepository.insertRole(operatorId, role, now));
    invitationRepository.markAccepted(invitation.id(), operatorId, now);
    auditLogRepository.insert(
        operatorId,
        "OPERATOR_INVITATION_ACCEPTED",
        "OPERATOR_INVITATION",
        invitation.id(),
        null,
        now);
    roles.forEach(
        role ->
            auditLogRepository.insert(
                operatorId, "OPERATOR_ROLE_GRANTED", "OPERATOR", operatorId, role.name(), now));
    sessionManager.begin(
        request, response, new OperatorPrincipal(operatorId, invitation.inviteeName(), roles));
    return OpsAuthMapper.toAcceptResponse(operatorId, invitation.inviteeName(), roles, now);
  }

  private OperatorInvitationRepository.Row enrolling(HttpSession session) {
    long invitationId =
        sessionState
            .enrollmentInvitation(session)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    OperatorInvitationRepository.Row invitation =
        invitationRepository
            .lockById(invitationId)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    requireUsable(invitation);
    if (invitation.kakaoProviderKey() == null) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
    return invitation;
  }

  private OperatorInvitationRepository.Row usableByToken(String token) {
    OperatorInvitationRepository.Row invitation =
        invitationRepository
            .findByTokenHash(sha256(token))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    requireUsable(invitation);
    return invitation;
  }

  private void requireUsable(OperatorInvitationRepository.Row invitation) {
    InvitationStatus status = invitation.status();
    if (status == InvitationStatus.PENDING && !invitation.expiresAt().isAfter(clock.instant())) {
      status = InvitationStatus.EXPIRED;
    }
    if (status != InvitationStatus.PENDING) {
      throw BusinessException.invitationNotUsable(status.name());
    }
  }

  public static String sha256(String token) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
    }
  }
}
