package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.ApprovedAgreementRow;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.PriceScheduleRow;
import com.sairo.be.domain.billing.service.PriceCalculator.PriceTable;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class PriceScheduleSelector {

  private final BillingQueryRepository queryRepository;

  public PriceScheduleSelector(BillingQueryRepository queryRepository) {
    this.queryRepository = queryRepository;
  }

  public PriceSelection select(long officeId, Instant at) {
    return find(officeId, at)
        .orElseThrow(() -> new BusinessException(ErrorCode.PRICE_SCHEDULE_NOT_PUBLISHED));
  }

  public Optional<PriceSelection> find(long officeId, Instant at) {
    Optional<ApprovedAgreementRow> agreement =
        queryRepository
            .findApprovedAgreement(officeId)
            .or(() -> queryRepository.findRevokedAgreementStillPriced(officeId, at));
    if (agreement.isPresent()
        && !at.isBefore(agreement.get().priceStartsAt())
        && at.isBefore(agreement.get().priceEndsAt())) {
      return queryRepository
          .findPriceById(agreement.get().priceScheduleId())
          .map(row -> new PriceSelection(row, true));
    }
    return queryRepository.findStandardPriceAt(at).map(row -> new PriceSelection(row, false));
  }

  public record PriceSelection(PriceScheduleRow schedule, boolean startCustomer) {

    public PriceTable table() {
      return new PriceTable(
          schedule.soloAmount(), schedule.teamFirstExtra(), schedule.teamAfterFourExtra());
    }

    public String version() {
      return schedule.version();
    }

    public String kind() {
      return startCustomer ? "START_CUSTOMER" : "STANDARD";
    }
  }
}
