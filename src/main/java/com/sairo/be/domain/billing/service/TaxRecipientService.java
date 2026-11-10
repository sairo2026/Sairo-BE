package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.dto.request.TaxRecipientUpsertRequest;
import com.sairo.be.domain.billing.dto.response.TaxRecipientResponse;
import com.sairo.be.domain.billing.entity.RecipientStatus;
import com.sairo.be.domain.billing.entity.TaxInvoiceRecipient;
import com.sairo.be.domain.billing.mapper.AgreementNoticeMapper;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.TaxInvoiceRecipientRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.AdvisoryLock;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class TaxRecipientService {

  private static final Pattern EMAIL =
      Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$");
  private static final int[] WEIGHTS = {1, 3, 7, 1, 3, 7, 1, 3, 5};

  private final TaxInvoiceRecipientRepository recipientRepository;
  private final BillingQueryRepository queryRepository;
  private final AdvisoryLock advisoryLock;
  private final Clock clock;

  public TaxRecipientService(
      TaxInvoiceRecipientRepository recipientRepository,
      BillingQueryRepository queryRepository,
      AdvisoryLock advisoryLock,
      Clock clock) {
    this.recipientRepository = recipientRepository;
    this.queryRepository = queryRepository;
    this.advisoryLock = advisoryLock;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public TaxRecipientResponse get(StaffPrincipal principal) {
    Optional<TaxInvoiceRecipient> active =
        recipientRepository.findByOfficeIdAndStatus(principal.officeId(), RecipientStatus.ACTIVE);
    if (active.isPresent()) {
      return AgreementNoticeMapper.toRecipient(active.get());
    }
    return AgreementNoticeMapper.toRecipientSuggestion(
        queryRepository
            .findOfficeSummary(principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
  }

  @Transactional
  public TaxRecipientResponse save(StaffPrincipal principal, TaxRecipientUpsertRequest request) {
    String businessNumber = request.businessNumber().strip();
    String email = request.receiverEmail().strip();
    if (!isValidBusinessNumber(businessNumber) || !EMAIL.matcher(email).matches()) {
      throw new BusinessException(ErrorCode.TAX_RECIPIENT_INVALID);
    }
    Long officeId = principal.officeId();
    advisoryLock.lockForTransaction("billing:tax-recipient:" + officeId);
    Instant now = clock.instant();
    recipientRepository
        .findByOfficeIdAndStatus(officeId, RecipientStatus.ACTIVE)
        .ifPresent(
            previous -> {
              previous.supersede(now);
              recipientRepository.saveAndFlush(previous);
            });
    TaxInvoiceRecipient saved =
        recipientRepository.saveAndFlush(
            TaxInvoiceRecipient.register(
                officeId,
                businessNumber,
                request.companyName().strip(),
                request.representativeName().strip(),
                email,
                request.taxType(),
                request.issueRequested(),
                principal.membershipId(),
                now));
    return AgreementNoticeMapper.toRecipient(saved);
  }

  static boolean isValidBusinessNumber(String value) {
    if (value == null || !value.matches("[0-9]{10}")) {
      return false;
    }
    int sum = 0;
    for (int i = 0; i < WEIGHTS.length; i++) {
      sum += (value.charAt(i) - '0') * WEIGHTS[i];
    }
    sum += (value.charAt(8) - '0') * 5 / 10;
    int check = (10 - sum % 10) % 10;
    return check == value.charAt(9) - '0';
  }
}
