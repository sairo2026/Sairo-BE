package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.legal.service.RetentionRuleLookup;
import com.sairo.be.domain.visit.dto.response.GuestPrivacyIntakeResponse;
import com.sairo.be.domain.visit.repository.GuestPrivacyIntakeRepository;
import com.sairo.be.domain.visit.repository.GuestPrivacyIntakeRepository.AccessLogRow;
import com.sairo.be.domain.visit.repository.GuestPrivacyIntakeRepository.AckRow;
import com.sairo.be.domain.visit.repository.GuestPrivacyIntakeRepository.IntakeRow;
import com.sairo.be.domain.visit.repository.VisitApproverPropertyLinkRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerLinkRepository;
import com.sairo.be.domain.visit.service.GuestPrivacyIntakeParser.Intake;
import com.sairo.be.domain.visit.service.GuestPrivacyIntakeParser.LinkKind;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.SecurityProperties;
import com.sairo.be.global.support.HmacSupport;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.global.support.TextCipher;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class GuestPrivacyIntakeService {

  static final int RESULT_DUE_DAYS = 10;
  static final int INTERNAL_DUE_DAYS = 7;
  static final int ACCESS_LOG_DEFAULT_DAYS = 365;
  static final int HMAC_KEY_VERSION = 1;
  static final String ACCESS_LOG_RULE = "GUEST_LINK_ACCESS_LOG";

  private record Resolved(Long linkId, Long officeId, String noticeCode, String noticeVersion) {}

  private final PublicLinkResolver customerResolver;
  private final PublicApproverLinkResolver approverResolver;
  private final CustomerLinkNoticeProvider customerNotice;
  private final ApproverLinkNoticeProvider approverNotice;
  private final GuestPrivacyIntakeRepository repository;
  private final RetentionRuleLookup retentionRules;
  private final RequestRateLimiter rateLimiter;
  private final TextCipher cipher;
  private final SecurityProperties securityProperties;
  private final Clock clock;

  public GuestPrivacyIntakeService(
      PublicLinkResolver customerResolver,
      PublicApproverLinkResolver approverResolver,
      CustomerLinkNoticeProvider customerNotice,
      ApproverLinkNoticeProvider approverNotice,
      GuestPrivacyIntakeRepository repository,
      RetentionRuleLookup retentionRules,
      RequestRateLimiter rateLimiter,
      TextCipher cipher,
      SecurityProperties securityProperties,
      Clock clock) {
    this.customerResolver = customerResolver;
    this.approverResolver = approverResolver;
    this.customerNotice = customerNotice;
    this.approverNotice = approverNotice;
    this.repository = repository;
    this.retentionRules = retentionRules;
    this.rateLimiter = rateLimiter;
    this.cipher = cipher;
    this.securityProperties = securityProperties;
    this.clock = clock;
  }

  @Transactional
  public GuestPrivacyIntakeResponse receive(JsonNode body, String clientIp, String userAgent) {
    Intake intake = GuestPrivacyIntakeParser.parse(body);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Resolved link = resolve(intake, clientIp, now);
    if (!link.noticeVersion().equals(intake.acknowledgedNoticeVersion())) {
      throw new BusinessException(ErrorCode.NOTICE_VERSION_CHANGED);
    }
    rateLimiter.hit(VisitRateLimits.PRIVACY_INTAKE_LINK, intake.linkKind() + ":" + link.linkId());
    rateLimiter.hit(VisitRateLimits.PRIVACY_INTAKE_IP, clientIp);

    String kind = intake.linkKind().name();
    repository.insertIntake(
        new IntakeRow(
            link.officeId(),
            kind,
            link.linkId(),
            intake.requestType().name(),
            intake.requesterRole().name(),
            intake.contactChannel().name(),
            cipher.encrypt(intake.contact()),
            intake.requestNote(),
            now,
            now.plus(Duration.ofDays(INTERNAL_DUE_DAYS))));
    repository.insertAck(
        new AckRow(
            link.officeId(), kind, link.linkId(), link.noticeCode(), link.noticeVersion(), now));
    repository.insertAccessLog(
        new AccessLogRow(
            kind,
            link.linkId(),
            link.officeId(),
            now,
            HmacSupport.hmacHex(securityProperties.rateLimitHmacSecret(), "link-ip|" + clientIp),
            userAgent == null
                ? null
                : HmacSupport.hmacHex(
                    securityProperties.rateLimitHmacSecret(), "link-ua|" + userAgent),
            HMAC_KEY_VERSION,
            TimeRules.displayDate(now.plus(Duration.ofDays(accessLogDays())))));
    return new GuestPrivacyIntakeResponse(
        TimeRules.toDisplayOffset(now),
        TimeRules.toDisplayOffset(now.plus(Duration.ofDays(RESULT_DUE_DAYS))),
        intake.contactChannel().name(),
        GuestPrivacyIntakeParser.mask(intake.contactChannel(), intake.contact()));
  }

  private Resolved resolve(Intake intake, String clientIp, Instant now) {
    if (intake.linkKind() == LinkKind.CUSTOMER) {
      VisitCustomerLinkRepository.LinkHead head =
          customerResolver.resolve(intake.token(), clientIp);
      if (!now.isBefore(head.getExpiresAt())) {
        throw new BusinessException(ErrorCode.PUBLIC_LINK_EXPIRED);
      }
      Long officeId =
          repository
              .officeOfCoordination(head.getCoordinationId())
              .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
      CustomerLinkNoticeProvider.Notice notice = customerNotice.current();
      return new Resolved(head.getId(), officeId, notice.noticeCode(), notice.noticeVersion());
    }
    VisitApproverPropertyLinkRepository.LinkHead head =
        approverResolver.resolve(intake.token(), clientIp);
    Long officeId =
        repository
            .officeOfApproval(head.getApprovalId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    ApproverLinkNoticeProvider.Notice notice = approverNotice.current();
    return new Resolved(head.getId(), officeId, notice.noticeCode(), notice.noticeVersion());
  }

  private int accessLogDays() {
    return retentionRules.retentionDays(ACCESS_LOG_RULE).orElse(ACCESS_LOG_DEFAULT_DAYS);
  }
}
