package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.dto.response.StartCustomerAgreementResponse;
import com.sairo.be.domain.billing.entity.AgreementStatus;
import com.sairo.be.domain.billing.entity.StartCustomerAgreement;
import com.sairo.be.domain.billing.mapper.AgreementNoticeMapper;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.PriceScheduleRow;
import com.sairo.be.domain.billing.repository.StartCustomerAgreementRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.AdvisoryLock;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class StartCustomerAgreementService {

  private final StartCustomerAgreementRepository agreementRepository;
  private final BillingQueryRepository queryRepository;
  private final BillingContextService context;
  private final AdvisoryLock advisoryLock;
  private final Clock clock;

  public StartCustomerAgreementService(
      StartCustomerAgreementRepository agreementRepository,
      BillingQueryRepository queryRepository,
      BillingContextService context,
      AdvisoryLock advisoryLock,
      Clock clock) {
    this.agreementRepository = agreementRepository;
    this.queryRepository = queryRepository;
    this.context = context;
    this.advisoryLock = advisoryLock;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public StartCustomerAgreementResponse get(StaffPrincipal principal) {
    StartCustomerAgreement agreement =
        agreementRepository
            .findByOfficeIdAndStatus(principal.officeId(), AgreementStatus.APPROVED)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    return toResponse(agreement);
  }

  @Transactional
  public StartCustomerAgreementResponse acknowledge(StaffPrincipal principal) {
    Long officeId = principal.officeId();
    advisoryLock.lockForTransaction("billing:start-customer-ack:" + officeId);
    StartCustomerAgreement agreement =
        agreementRepository
            .findByOfficeIdAndStatus(officeId, AgreementStatus.APPROVED)
            .orElseThrow(() -> missingApprovedAgreement(officeId));
    if (!agreement.isAcknowledged()) {
      agreement.acknowledge(principal.membershipId(), clock.instant());
      agreementRepository.saveAndFlush(agreement);
    }
    return toResponse(agreement);
  }

  private BusinessException missingApprovedAgreement(Long officeId) {
    boolean registered =
        agreementRepository
            .findByOfficeIdAndStatus(officeId, AgreementStatus.REGISTERED)
            .isPresent();
    return new BusinessException(
        registered ? ErrorCode.INVALID_TRANSITION : ErrorCode.RESOURCE_NOT_FOUND);
  }

  private StartCustomerAgreementResponse toResponse(StartCustomerAgreement agreement) {
    PriceScheduleRow price =
        queryRepository
            .findPriceById(agreement.getPriceScheduleId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PRICE_SCHEDULE_NOT_PUBLISHED));
    int memberCount = context.billableMembers(agreement.getOfficeId());
    long amount =
        PriceCalculator.monthlyAmount(
            new PriceCalculator.PriceTable(
                price.soloAmount(), price.teamFirstExtra(), price.teamAfterFourExtra()),
            memberCount);
    Instant freeEndsAt =
        queryRepository.findTrialEventFixedEnd(agreement.getTrialEventId()).orElse(null);
    return AgreementNoticeMapper.toAgreement(
        agreement, freeEndsAt, price.version(), memberCount, amount);
  }
}
