package com.sairo.be.domain.billing.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.billing.dto.request.ArchiveGrantCreateRequest;
import com.sairo.be.domain.billing.dto.response.ArchiveGrantCreateResponse;
import com.sairo.be.domain.billing.dto.response.GrantStatusResponse;
import com.sairo.be.domain.billing.entity.ArchiveGrant;
import com.sairo.be.domain.billing.repository.ArchiveGrantRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.GrantPrincipal;
import com.sairo.be.global.security.GrantType;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class ArchiveGrantService {

  private final ArchiveGrantRepository grantRepository;
  private final BillingQueryRepository queryRepository;
  private final Clock clock;

  public ArchiveGrantService(
      ArchiveGrantRepository grantRepository, BillingQueryRepository queryRepository, Clock clock) {
    this.grantRepository = grantRepository;
    this.queryRepository = queryRepository;
    this.clock = clock;
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
