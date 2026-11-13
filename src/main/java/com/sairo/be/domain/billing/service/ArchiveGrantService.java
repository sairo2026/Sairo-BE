package com.sairo.be.domain.billing.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.billing.dto.request.ArchiveGrantCreateRequest;
import com.sairo.be.domain.billing.dto.response.ArchiveGrantCreateResponse;
import com.sairo.be.domain.billing.dto.response.ExportResponse;
import com.sairo.be.domain.billing.dto.response.GrantStatusResponse;
import com.sairo.be.domain.billing.entity.ArchiveGrant;
import com.sairo.be.domain.billing.entity.ExportJob;
import com.sairo.be.domain.billing.repository.ArchiveGrantRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.ExportJobRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.global.security.GrantPrincipal;
import com.sairo.be.global.security.GrantType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class ArchiveGrantService {

  private final ArchiveGrantRepository grantRepository;
  private final BillingQueryRepository queryRepository;
  private final ExportJobRepository exportJobRepository;
  private final ObjectStorage objectStorage;
  private final Clock clock;

  public ArchiveGrantService(
      ArchiveGrantRepository grantRepository,
      BillingQueryRepository queryRepository,
      ExportJobRepository exportJobRepository,
      ObjectStorage objectStorage,
      Clock clock) {
    this.grantRepository = grantRepository;
    this.queryRepository = queryRepository;
    this.exportJobRepository = exportJobRepository;
    this.objectStorage = objectStorage;
    this.clock = clock;
  }

  @Transactional
  public ExportResponse createExport(GrantPrincipal principal, Long grantId) {
    ArchiveGrant grant = requireUsable(principal, grantId);
    Long membershipId =
        queryRepository
            .findLatestMembershipId(grant.getGrantedToUserId(), grant.getOfficeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.ARCHIVE_GRANT_INVALID));
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    ExportJob job =
        exportJobRepository.saveAndFlush(
            ExportJob.archiveExport(
                grant.getOfficeId(),
                membershipId,
                grant.getId(),
                now,
                now.plus(ReturnGrantService.FILE_RETENTION)));
    return new ExportResponse(
        job.getId(),
        job.getStatus(),
        null,
        null,
        toDisplayOffset(job.getFileExpiresAt()),
        job.getFailureReason());
  }

  @Transactional(readOnly = true)
  public ExportResponse exportStatus(GrantPrincipal principal, Long grantId, Long exportId) {
    ArchiveGrant grant = requireUsable(principal, grantId);
    ExportJob job =
        exportJobRepository
            .findByIdAndOfficeIdAndArchiveGrantId(exportId, grant.getOfficeId(), grant.getId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Instant now = clock.instant();
    if (!now.isBefore(job.getFileExpiresAt())) {
      throw new BusinessException(ErrorCode.EXPORT_EXPIRED);
    }
    String url = null;
    Instant urlExpiresAt = null;
    if (job.isCompleted() && job.getFileObjectKey() != null) {
      url =
          objectStorage
              .issueDownloadUrl(job.getFileObjectKey(), ReturnGrantService.DOWNLOAD_URL_VALIDITY)
              .toString();
      urlExpiresAt = now.plus(ReturnGrantService.DOWNLOAD_URL_VALIDITY);
    }
    return new ExportResponse(
        job.getId(),
        job.getStatus(),
        url,
        toDisplayOffset(urlExpiresAt),
        toDisplayOffset(job.getFileExpiresAt()),
        job.getFailureReason());
  }

  private ArchiveGrant requireUsable(GrantPrincipal principal, Long pathGrantId) {
    if (principal.grantType() != GrantType.ARCHIVE) {
      throw new BusinessException(ErrorCode.ARCHIVE_GRANT_INVALID);
    }
    if (!pathGrantId.equals(principal.grantId())) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    return grantRepository
        .findById(principal.grantId())
        .filter(found -> found.getGrantedToUserId().equals(principal.userId()))
        .filter(found -> found.isUsable(clock.instant()))
        .orElseThrow(() -> new BusinessException(ErrorCode.ARCHIVE_GRANT_INVALID));
  }

  @Transactional
  public ArchiveGrantCreateResponse create(ArchiveGrantCreateRequest request) {
    if (!queryRepository.officeExists(request.officeId())
        || !queryRepository.userExists(request.grantedToUserId())) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    ArchiveGrant grant =
        grantRepository.saveAndFlush(
            ArchiveGrant.issue(
                request.officeId(),
                request.grantedToUserId(),
                request.contractReference().strip(),
                clock.instant().truncatedTo(ChronoUnit.MICROS)));
    return new ArchiveGrantCreateResponse(
        grant.getId(),
        grant.getOfficeId(),
        toDisplayOffset(grant.getExpiresAt()),
        grant.getTerminatedAt() != null);
  }

  @Transactional(readOnly = true)
  public GrantStatusResponse current(GrantPrincipal principal) {
    if (principal.grantType() != GrantType.ARCHIVE) {
      throw new BusinessException(ErrorCode.ARCHIVE_GRANT_INVALID);
    }
    ArchiveGrant grant =
        grantRepository
            .findById(principal.grantId())
            .filter(found -> found.getGrantedToUserId().equals(principal.userId()))
            .filter(found -> found.isUsable(clock.instant()))
            .orElseThrow(() -> new BusinessException(ErrorCode.ARCHIVE_GRANT_INVALID));
    return new GrantStatusResponse(
        grant.getId(),
        grant.getOfficeId(),
        toDisplayOffset(grant.getExpiresAt()),
        grant.getTerminatedAt() != null);
  }
}
