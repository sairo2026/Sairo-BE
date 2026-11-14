package com.sairo.be.domain.billing.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.auth.dto.response.ChangedTermsItem;
import com.sairo.be.domain.auth.entity.ConsentPath;
import com.sairo.be.domain.auth.entity.TermsAgreement;
import com.sairo.be.domain.auth.repository.TermsAgreementRepository;
import com.sairo.be.domain.billing.dto.request.BillingKeyRegisterRequest;
import com.sairo.be.domain.billing.dto.response.BillingKeyRegisterResponse;
import com.sairo.be.domain.billing.dto.response.BillingKeySessionResponse;
import com.sairo.be.domain.billing.entity.BillingCustomer;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.repository.BillingCustomerRepository;
import com.sairo.be.domain.legal.entity.TermsDisplayContext;
import com.sairo.be.domain.legal.service.TermsCatalogService;
import com.sairo.be.domain.legal.service.TermsVersionView;
import com.sairo.be.global.config.BillingProperties;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.port.PaymentGateway;
import com.sairo.be.global.port.PaymentGateway.BillingKeyIssueException;
import com.sairo.be.global.port.PaymentGateway.BillingKeyResult;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.TextCipher;
import com.sairo.be.global.support.TokenGenerator;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Profile("!migrate")
@Service
public class BillingKeyService {

  private final BillingContextService context;
  private final BillingCustomerRepository customerRepository;
  private final TermsAgreementRepository termsAgreementRepository;
  private final TermsCatalogService termsCatalogService;
  private final PaymentGateway paymentGateway;
  private final TextCipher billingKeyCipher;
  private final TokenGenerator tokenGenerator;
  private final StepUpState stepUpState;
  private final AdvisoryLock advisoryLock;
  private final BillingProperties properties;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public BillingKeyService(
      BillingContextService context,
      BillingCustomerRepository customerRepository,
      TermsAgreementRepository termsAgreementRepository,
      TermsCatalogService termsCatalogService,
      PaymentGateway paymentGateway,
      @Qualifier("billingKeyCipher") TextCipher billingKeyCipher,
      TokenGenerator tokenGenerator,
      StepUpState stepUpState,
      AdvisoryLock advisoryLock,
      BillingProperties properties,
      TransactionTemplate transactionTemplate,
      Clock clock) {
    this.context = context;
    this.customerRepository = customerRepository;
    this.termsAgreementRepository = termsAgreementRepository;
    this.termsCatalogService = termsCatalogService;
    this.paymentGateway = paymentGateway;
    this.billingKeyCipher = billingKeyCipher;
    this.tokenGenerator = tokenGenerator;
    this.stepUpState = stepUpState;
    this.advisoryLock = advisoryLock;
    this.properties = properties;
    this.transactionTemplate = transactionTemplate;
    this.clock = clock;
  }

  public BillingKeySessionResponse openSession(StaffPrincipal principal, HttpSession session) {
    stepUpState.require(session);
    Long officeId = principal.officeId();
    return transactionTemplate.execute(
        status -> {
          advisoryLock.lockForTransaction(lockKey(officeId));
          Instant now = clock.instant();
          Instant expiresAt = now.plus(properties.customerKeyTtl());
          BillingCustomer customer =
              customerRepository
                  .findByOfficeId(officeId)
                  .map(
                      existing -> {
                        if (existing.hasBillingKey()) {
                          existing.refreshKeyWindow(now, expiresAt);
                        } else {
                          existing.rotateCustomerKey(tokenGenerator.randomToken(), now, expiresAt);
                        }
                        return existing;
                      })
                  .orElseGet(
                      () ->
                          BillingCustomer.open(
                              officeId, tokenGenerator.randomToken(), now, expiresAt));
          customerRepository.saveAndFlush(customer);
          return new BillingKeySessionResponse(
              customer.getTossCustomerKey(), toDisplayOffset(expiresAt));
        });
  }

  public BillingKeyRegisterResponse register(
      StaffPrincipal principal, HttpSession session, BillingKeyRegisterRequest request) {
    stepUpState.require(session);
    Long officeId = principal.officeId();
    TermsVersionView cardNotice = requireCardNoticeVersion(request.cardNoticeVersion());
    BillingCustomer customer = requireOpenSession(officeId, request.customerKey());
    context.requireSubscription(officeId);

    BillingKeyResult issued;
    try {
      issued = paymentGateway.issueBillingKey(request.authKey(), customer.getTossCustomerKey());
    } catch (BillingKeyIssueException e) {
      throw new BusinessException(ErrorCode.BILLING_KEY_ISSUE_FAILED);
    }
    String encrypted = billingKeyCipher.encrypt(issued.billingKey());
    String last4 = lastFourDigits(issued.maskedCardNumber());

    BillingKeyRegisterResponse response =
        transactionTemplate.execute(
            status -> {
              advisoryLock.lockForTransaction(lockKey(officeId));
              Instant now = clock.instant();
              BillingCustomer locked =
                  customerRepository
                      .findByOfficeId(officeId)
                      .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
              locked.registerBillingKey(encrypted, issued.cardIssuer(), last4, now);
              customerRepository.saveAndFlush(locked);
              if (cardNotice != null) {
                termsAgreementRepository.save(
                    TermsAgreement.recordForOffice(
                        principal.userId(),
                        officeId,
                        cardNotice.termsCode(),
                        cardNotice.version(),
                        true,
                        ConsentPath.CARD_REGISTRATION,
                        now));
              }
              Subscription subscription = context.requireSubscription(officeId);
              subscription.markEventRecorded(now);
              return new BillingKeyRegisterResponse(
                  true,
                  toDisplayOffset(now),
                  toDisplayOffset(subscription.getFirstBillingScheduledAt()));
            });
    stepUpState.consume(session);
    return response;
  }

  public void delete(StaffPrincipal principal, HttpSession session) {
    stepUpState.require(session);
    Long officeId = principal.officeId();
    String removedKey =
        transactionTemplate.execute(
            status -> {
              advisoryLock.lockForTransaction(lockKey(officeId));
              Optional<BillingCustomer> customer = customerRepository.findByOfficeId(officeId);
              if (customer.isPresent() && customer.get().hasBillingKey()) {
                Instant now = clock.instant();
                String encrypted = customer.get().getBillingKeyEncrypted();
                customer.get().clearBillingKey(now);
                customerRepository.saveAndFlush(customer.get());
                context.requireSubscription(officeId).markEventRecorded(now);
                return encrypted;
              }
              return null;
            });
    stepUpState.consume(session);
    if (removedKey != null) {
      try {
        paymentGateway.deleteBillingKey(billingKeyCipher.decrypt(removedKey));
      } catch (RuntimeException ignored) {
      }
    }
  }

  private TermsVersionView requireCardNoticeVersion(String submittedVersion) {
    List<TermsVersionView> inForce =
        termsCatalogService.findInForce(TermsDisplayContext.CARD_REGISTRATION);
    if (inForce.isEmpty()) {
      return null;
    }
    TermsVersionView current = inForce.get(0);
    if (!current.version().equals(submittedVersion)) {
      throw BusinessException.termsVersionChanged(
          List.of(
              new ChangedTermsItem(
                  current.termsCode(),
                  submittedVersion,
                  current.version(),
                  current.changeSummary(),
                  current.documentUrl())));
    }
    return current;
  }

  private BillingCustomer requireOpenSession(Long officeId, String submittedKey) {
    BillingCustomer customer =
        customerRepository
            .findByOfficeId(officeId)
            .filter(found -> found.getTossCustomerKey().equals(submittedKey))
            .filter(found -> found.isKeyWindowOpen(clock.instant()))
            .orElseThrow(
                () ->
                    BusinessException.withFieldErrors(
                        ErrorCode.INVALID_REQUEST,
                        List.of(
                            new FieldErrorItem(
                                "customerKey",
                                FieldErrorCode.INVALID_VALUE,
                                "카드 등록 세션의 customerKey와 일치하지 않거나 만료되었습니다."))));
    return customer;
  }

  static String lastFourDigits(String maskedCardNumber) {
    if (maskedCardNumber == null) {
      return null;
    }
    String digits = maskedCardNumber.replaceAll("[^0-9]", "");
    return digits.length() < 4 ? null : digits.substring(digits.length() - 4);
  }

  private static String lockKey(Long officeId) {
    return "billing:customer:" + officeId;
  }
}
