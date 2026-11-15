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
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.BusinessNumbers;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    String email = request.receiverEmail().strip();
    List<FieldErrorItem> invalid = new ArrayList<>();
    String businessNumber = BusinessNumbers.normalize(request.businessNumber()).orElse(null);
    if (businessNumber == null) {
      invalid.add(
          new FieldErrorItem(
              "businessNumber", FieldErrorCode.INVALID_FORMAT, "사업자등록번호는 숫자 10자리여야 합니다."));
    } else if (!BusinessNumbers.hasValidChecksum(businessNumber)) {
      invalid.add(
          new FieldErrorItem(
              "businessNumber",
              FieldErrorCode.INVALID_VALUE,
              "존재하지 않는 사업자등록번호입니다. 번호를 다시 확인해 주세요."));
    }
    if (!EMAIL.matcher(email).matches()) {
      invalid.add(
          new FieldErrorItem("receiverEmail", FieldErrorCode.INVALID_FORMAT, "이메일 형식이 올바르지 않습니다."));
    }
    if (!invalid.isEmpty()) {
      throw BusinessException.withFieldErrors(ErrorCode.TAX_RECIPIENT_INVALID, invalid);
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
}
