package com.sairo.be.domain.coordination.service;

import com.sairo.be.domain.coordination.dto.response.HomeSummaryResponse;
import com.sairo.be.domain.coordination.entity.CoordinationStatus;
import com.sairo.be.domain.coordination.repository.CoordinationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class HomeSummaryService {

  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

  private final CoordinationRepository coordinationRepository;

  public HomeSummaryService(CoordinationRepository coordinationRepository) {
    this.coordinationRepository = coordinationRepository;
  }

  @Transactional(readOnly = true)
  public HomeSummaryResponse getSummary(Long officeId) {
    LocalDate today = LocalDate.now(BUSINESS_ZONE);
    Instant startInclusive = today.atStartOfDay(BUSINESS_ZONE).toInstant();
    Instant endExclusive = today.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();

    HomeSummaryResponse.CoordinationStatusCounts counts =
        new HomeSummaryResponse.CoordinationStatusCounts(
            count(officeId, CoordinationStatus.TENANT_CHECKING),
            count(officeId, CoordinationStatus.BUYER_DELIVERY_REQUIRED),
            count(officeId, CoordinationStatus.BUYER_CHECKING),
            count(officeId, CoordinationStatus.FINAL_CONFIRMATION_REQUIRED),
            count(officeId, CoordinationStatus.SCHEDULE_CONFIRMED),
            count(officeId, CoordinationStatus.VISIT_COMPLETED));
    long todayVisitCount =
        coordinationRepository
            .countByOfficeIdAndStatusAndScheduledAtGreaterThanEqualAndScheduledAtLessThan(
                officeId, CoordinationStatus.SCHEDULE_CONFIRMED, startInclusive, endExclusive);
    long inProgressCoordinationCount =
        counts.tenantChecking()
            + counts.buyerDeliveryRequired()
            + counts.buyerChecking()
            + counts.finalConfirmationRequired()
            + counts.scheduleConfirmed();
    return new HomeSummaryResponse(todayVisitCount, inProgressCoordinationCount, 0, counts);
  }

  private long count(Long officeId, CoordinationStatus status) {
    return coordinationRepository.countByOfficeIdAndStatus(officeId, status);
  }
}
