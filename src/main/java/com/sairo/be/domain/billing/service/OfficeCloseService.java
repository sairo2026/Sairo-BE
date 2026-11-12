package com.sairo.be.domain.billing.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.billing.dto.response.OfficeCloseResponse;
import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.InvoiceStatus;
import com.sairo.be.domain.billing.entity.RefundReasonCode;
import com.sairo.be.domain.billing.entity.ReturnGrant;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.SubscriptionRepository;
import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.office.service.OfficeClosureMembershipService;
import com.sairo.be.domain.support.service.SupportInquiryService;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.global.support.AdvisoryLock;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 사무소 즉시 종료(SETTING-03, BILL-08·09)다. 한 트랜잭션에서 구독을 끝내고(미승인 청구 취소, 잔여기간 비례 환불 접수, 구매 동의 철회), 모든 구성원
 * 소속을 해제하고, 종료 직전 대표에게 30일 반환권한을 발급한다. 진행 중 대표 이전·미납·처리 중 청구는 종료를 막지 않고 대표가 확인한 요청만 처리한다.
 */
@Profile("!migrate")
@Service
public class OfficeCloseService {

  public static final String ARCHIVE_REQUEST = "REQUEST";
  public static final String ARCHIVE_DECLINE = "DECLINE";

  private static final Set<String> KEYS = Set.of("confirmed", "archiveChoice");
  private static final Set<String> ARCHIVE_CHOICES = Set.of(ARCHIVE_REQUEST, ARCHIVE_DECLINE);
  private static final List<InvoiceStatus> OPEN_INVOICES =
      List.of(InvoiceStatus.UNBILLED, InvoiceStatus.FAILED);

  private final OfficeClosureMembershipService membershipService;
  private final SubscriptionRepository subscriptionRepository;
  private final BillingInvoiceRepository invoiceRepository;
  private final RemainingPeriodRefunds remainingPeriodRefunds;
  private final ConsentWithdrawals consentWithdrawals;
  private final ReturnGrantService returnGrantService;
  private final SupportInquiryService supportInquiryService;
  private final StepUpState stepUpState;
  private final AdvisoryLock advisoryLock;
  private final Clock clock;

  public OfficeCloseService(
      OfficeClosureMembershipService membershipService,
      SubscriptionRepository subscriptionRepository,
      BillingInvoiceRepository invoiceRepository,
      RemainingPeriodRefunds remainingPeriodRefunds,
      ConsentWithdrawals consentWithdrawals,
      ReturnGrantService returnGrantService,
      SupportInquiryService supportInquiryService,
      StepUpState stepUpState,
      AdvisoryLock advisoryLock,
      Clock clock) {
    this.membershipService = membershipService;
    this.subscriptionRepository = subscriptionRepository;
    this.invoiceRepository = invoiceRepository;
    this.remainingPeriodRefunds = remainingPeriodRefunds;
    this.consentWithdrawals = consentWithdrawals;
    this.returnGrantService = returnGrantService;
    this.supportInquiryService = supportInquiryService;
    this.stepUpState = stepUpState;
    this.advisoryLock = advisoryLock;
    this.clock = clock;
  }

  @Transactional
  public OfficeCloseResponse close(StaffPrincipal principal, HttpSession session, JsonNode body) {
    String archiveChoice = parse(body);
    stepUpState.require(session);
    Long officeId = principal.officeId();
    if (!membershipService.lockAndCheckOwner(officeId, principal.membershipId())) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    endSubscription(officeId, now);
    membershipService.releaseAll(officeId, now);
    ReturnGrant grant = returnGrantService.issue(officeId, principal.userId(), now);
    String archiveStatus = "NONE";
    if (ARCHIVE_REQUEST.equals(archiveChoice)) {
      supportInquiryService.openForArchiveRequest(principal.userId(), officeId);
      archiveStatus = "REQUESTED";
    }
    stepUpState.consume(session);
    return new OfficeCloseResponse(
        officeId,
        SubscriptionStatus.ENDED.name(),
        grant.getId(),
        toDisplayOffset(grant.getExpiresAt()),
        archiveStatus);
  }

  /** 끝나지 않은 구독을 지금 끝낸다. 인증 중단 상태는 중단 때 이미 미납 취소·환불·동의 철회를 했으므로 상태만 바꾼다. */
  private void endSubscription(Long officeId, Instant now) {
    Optional<Subscription> found = subscriptionRepository.findByOfficeId(officeId);
    if (found.isEmpty()) {
      return;
    }
    advisoryLock.lockForTransaction("billing:subscription:" + found.get().getId());
    Subscription subscription = subscriptionRepository.findById(found.get().getId()).orElseThrow();
    if (subscription.getStatus() == SubscriptionStatus.ENDED) {
      return;
    }
    if (subscription.getStatus() != SubscriptionStatus.SUSPENDED) {
      List<BillingInvoice> open =
          invoiceRepository.findBySubscriptionIdAndStatusIn(subscription.getId(), OPEN_INVOICES);
      open.forEach(BillingInvoice::markCancelled);
      invoiceRepository.saveAll(open);
      remainingPeriodRefunds.request(subscription, now, RefundReasonCode.CANCELLATION);
      consentWithdrawals.record(subscription, now);
    }
    subscription.end(now);
    subscriptionRepository.save(subscription);
  }

  private static String parse(JsonNode body) {
    CustomerInput.requireObject(body, KEYS);
    Boolean confirmed = CustomerInput.requiredBoolean(body, "confirmed");
    if (!confirmed) {
      throw CustomerInput.invalid("confirmed", FieldErrorCode.NOT_ALLOWED, "종료 책임을 확인해야 합니다.");
    }
    String choice = CustomerInput.requiredText(body, "archiveChoice", 10, false);
    if (!ARCHIVE_CHOICES.contains(choice)) {
      throw CustomerInput.invalid(
          "archiveChoice", FieldErrorCode.NOT_ALLOWED, "REQUEST 또는 DECLINE이어야 합니다.");
    }
    return choice;
  }
}
