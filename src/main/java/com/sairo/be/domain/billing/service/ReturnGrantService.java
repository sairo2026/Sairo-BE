package com.sairo.be.domain.billing.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.billing.dto.response.ExportResponse;
import com.sairo.be.domain.billing.dto.response.GrantStatusResponse;
import com.sairo.be.domain.billing.entity.ExportJob;
import com.sairo.be.domain.billing.entity.ReturnGrant;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.ExportJobRepository;
import com.sairo.be.domain.billing.repository.ReturnGrantRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.global.security.GrantPrincipal;
import com.sairo.be.global.security.GrantType;
import com.sairo.be.global.support.AdvisoryLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class ReturnGrantService {

  static final Duration FILE_RETENTION = Duration.ofHours(24);
  static final Duration DOWNLOAD_URL_VALIDITY = Duration.ofMinutes(10);

  private final ReturnGrantRepository grantRepository;
  private final ExportJobRepository exportJobRepository;
  private final BillingQueryRepository queryRepository;
  private final ObjectStorage objectStorage;
  private final AdvisoryLock advisoryLock;
  private final Clock clock;

  public ReturnGrantService(
      ReturnGrantRepository grantRepository,
      ExportJobRepository exportJobRepository,
      BillingQueryRepository queryRepository,
      ObjectStorage objectStorage,
      AdvisoryLock advisoryLock,
      Clock clock) {
    this.grantRepository = grantRepository;
    this.exportJobRepository = exportJobRepository;
    this.queryRepository = queryRepository;
    this.objectStorage = objectStorage;
    this.advisoryLock = advisoryLock;
    this.clock = clock;
  }

  @Transactional
  public ReturnGrant issue(Long officeId, Long userId, Instant now) {
    advisoryLock.lockForTransaction("billing:return-grant:" + officeId);
    Instant grantedAt = now.truncatedTo(ChronoUnit.MICROS);
    ReturnGrant issued =
        grantRepository.saveAndFlush(ReturnGrant.issue(officeId, userId, grantedAt));
    for (ReturnGrant previous : grantRepository.findByOfficeIdAndRevokedAtIsNull(officeId)) {
      if (!previous.getId().equals(issued.getId())) {
        previous.revoke("재발급", issued.getId(), grantedAt);
        grantRepository.save(previous);
      }
    }
    return issued;
  }

  @Transactional(readOnly = true)
  public GrantStatusResponse current(GrantPrincipal principal) {
    ReturnGrant grant = requireUsable(principal, null);
    return new GrantStatusResponse(
        grant.getId(), toDisplayOffset(grant.getExpiresAt()), grant.getRevokedAt() != null);
  }

  @Transactional
  public ExportResponse createExport(GrantPrincipal principal, Long grantId) {
    ReturnGrant grant = requireUsable(principal, grantId);
    Long membershipId =
        queryRepository
            .findLatestMembershipId(grant.getGrantedToUserId(), grant.getOfficeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RETURN_GRANT_INVALID));
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    ExportJob job =
        exportJobRepository.saveAndFlush(
            ExportJob.fullExport(grant.getOfficeId(), membershipId, now, now.plus(FILE_RETENTION)));
    return new ExportResponse(
        job.getId(), job.getStatus(), null, null, toDisplayOffset(job.getFileExpiresAt()));
  }

  @Transactional(readOnly = true)
  public ExportResponse exportStatus(GrantPrincipal principal, Long grantId, Long exportId) {
    ReturnGrant grant = requireUsable(principal, grantId);
    ExportJob job =
        exportJobRepository
            .findByIdAndOfficeId(exportId, grant.getOfficeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Instant now = clock.instant();
    if (!now.isBefore(job.getFileExpiresAt())) {
      throw new BusinessException(ErrorCode.EXPORT_EXPIRED);
    }
    String url = null;
    Instant urlExpiresAt = null;
    if (job.isCompleted() && job.getFileObjectKey() != null) {
      url =
          objectStorage.issueDownloadUrl(job.getFileObjectKey(), DOWNLOAD_URL_VALIDITY).toString();
      urlExpiresAt = now.plus(DOWNLOAD_URL_VALIDITY);
    }
    return new ExportResponse(
        job.getId(),
        job.getStatus(),
        url,
        toDisplayOffset(urlExpiresAt),
        toDisplayOffset(job.getFileExpiresAt()));
  }

  /** 반환권한으로 기존 방문을 고치는 사람이다. 기록자는 반환권한 사용자의 보존된 사무소 소속이다. */
  public record VisitActor(Long grantId, Long officeId, Long userId, Long membershipId) {}

  /**
   * 반환권한 방문 경로(VISIT-09)의 매 요청 재검사다. 경로의 권한이 세션의 권한이고 만료·회수되지 않았으며 사용자가 권한자여야 한다. 활성 소속은 요구하지 않는다.
   * 사무소 인증이 회수됐으면 직접 변경을 거절한다.
   */
  public VisitActor requireVisitActor(GrantPrincipal principal, Long grantId) {
    ReturnGrant grant = requireUsable(principal, grantId);
    if (queryRepository.isOfficeRegistrationRevoked(grant.getOfficeId())) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    Long membershipId =
        queryRepository
            .findLatestMembershipId(grant.getGrantedToUserId(), grant.getOfficeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
    return new VisitActor(
        grant.getId(), grant.getOfficeId(), grant.getGrantedToUserId(), membershipId);
  }

  private ReturnGrant requireUsable(GrantPrincipal principal, Long pathGrantId) {
    if (principal.grantType() != GrantType.RETURN
        || (pathGrantId != null && !pathGrantId.equals(principal.grantId()))) {
      throw new BusinessException(ErrorCode.RETURN_GRANT_INVALID);
    }
    return grantRepository
        .findById(principal.grantId())
        .filter(grant -> grant.getGrantedToUserId().equals(principal.userId()))
        .filter(grant -> grant.isUsable(clock.instant()))
        .orElseThrow(() -> new BusinessException(ErrorCode.RETURN_GRANT_INVALID));
  }
}
