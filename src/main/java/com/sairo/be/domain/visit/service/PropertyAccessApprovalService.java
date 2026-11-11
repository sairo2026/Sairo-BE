package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.legal.service.RetentionRuleLookup;
import com.sairo.be.domain.property.entity.Property;
import com.sairo.be.domain.property.service.PropertyGuard;
import com.sairo.be.domain.visit.dto.response.AccessApprovalHistoryItem;
import com.sairo.be.domain.visit.dto.response.AccessApprovalResponse;
import com.sairo.be.domain.visit.entity.ApprovalRole;
import com.sairo.be.domain.visit.entity.ApproverType;
import com.sairo.be.domain.visit.entity.InputSource;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.repository.MembershipNameRepository;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.PhoneNumbers;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class PropertyAccessApprovalService {

  static final String RETENTION_RULE = "GUEST_INPUT_AFTER_APPROVAL_END";

  private static final Set<String> BODY_KEYS =
      Set.of(
          "approverType",
          "approverName",
          "approverPhone",
          "validFrom",
          "validUntil",
          "timeSlots",
          "source",
          "approvalRole",
          "confirmedAt");

  private final PropertyGuard propertyGuard;
  private final ApprovalAccess access;
  private final PropertyAccessApprovalRepository approvalRepository;
  private final MembershipNameRepository nameRepository;
  private final ApproverPhoneHasher phoneHasher;
  private final RetentionRuleLookup retentionRules;
  private final Clock clock;

  public PropertyAccessApprovalService(
      PropertyGuard propertyGuard,
      ApprovalAccess access,
      PropertyAccessApprovalRepository approvalRepository,
      MembershipNameRepository nameRepository,
      ApproverPhoneHasher phoneHasher,
      RetentionRuleLookup retentionRules,
      Clock clock) {
    this.propertyGuard = propertyGuard;
    this.access = access;
    this.approvalRepository = approvalRepository;
    this.nameRepository = nameRepository;
    this.phoneHasher = phoneHasher;
    this.retentionRules = retentionRules;
    this.clock = clock;
  }

  @Transactional
  public AccessApprovalResponse register(StaffPrincipal principal, Long propertyId, JsonNode body) {
    Input input = parse(body);
    Property property = propertyGuard.lockProperty(principal.officeId(), propertyId);
    propertyGuard.requireNotArchived(property);

    if (input.role() == ApprovalRole.PRIMARY
        && approvalRepository.existsActivePrimaryOverlap(
            propertyId, input.validFrom(), input.validUntil())) {
      throw new BusinessException(ErrorCode.PRIMARY_APPROVER_EXISTS);
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    boolean proxy = input.source() == InputSource.PROXY;
    PropertyAccessApproval saved =
        approvalRepository.saveAndFlush(
            PropertyAccessApproval.register(
                propertyId,
                input.type(),
                input.name(),
                input.phone(),
                input.phone() == null ? null : phoneHasher.hash(input.phone()),
                input.role(),
                input.validFrom(),
                input.validUntil(),
                input.source(),
                proxy ? principal.membershipId() : null,
                input.confirmedAt(),
                now,
                TimeRules.displayDate(input.validUntil()).plusDays(retentionDays())));
    return new AccessApprovalResponse(
        saved.getId(),
        propertyId,
        saved.getApproverType().name(),
        saved.getApproverName(),
        saved.getApproverPhone(),
        saved.getApprovalRole().name(),
        TimeRules.toDisplayOffset(saved.getValidFrom()),
        TimeRules.toDisplayOffset(saved.getValidUntil()),
        List.of(),
        saved.getSource().name(),
        saved.getRecordedByMembershipId(),
        proxy ? principal.name() : null,
        offset(saved.getConfirmedAt()),
        TimeRules.toDisplayOffset(saved.getRecordedAt()),
        property.getOfficeId(),
        property.getVersion(),
        null,
        null,
        null,
        null);
  }

  @Transactional(readOnly = true)
  public PageResponse<AccessApprovalHistoryItem> history(
      StaffPrincipal principal, Long propertyId, PageQuery pageQuery) {
    access.requireOwnProperty(principal, propertyId);
    Page<PropertyAccessApproval> page =
        approvalRepository.findByPropertyId(
            propertyId,
            pageQuery.toPageable(Sort.by(Sort.Order.desc("recordedAt"), Sort.Order.desc("id"))));
    Set<Long> memberIds = new HashSet<>();
    page.forEach(
        approval -> {
          if (approval.getRecordedByMembershipId() != null) {
            memberIds.add(approval.getRecordedByMembershipId());
          }
        });
    Map<Long, String> names = nameRepository.displayNames(memberIds);
    return PageResponse.from(page, approval -> historyItem(approval, names));
  }

  private static AccessApprovalHistoryItem historyItem(
      PropertyAccessApproval approval, Map<Long, String> names) {
    return new AccessApprovalHistoryItem(
        approval.getId(),
        approval.getPropertyId(),
        approval.getApproverType().name(),
        approval.getApproverName(),
        approval.getApproverPhone(),
        approval.getApprovalRole().name(),
        TimeRules.toDisplayOffset(approval.getValidFrom()),
        TimeRules.toDisplayOffset(approval.getValidUntil()),
        approval.getSource().name(),
        approval.getRecordedByMembershipId(),
        TimeRules.toDisplayOffset(approval.getRecordedAt()),
        offset(approval.getConfirmedAt()),
        approval.getRecordedByMembershipId() == null
            ? null
            : names.get(approval.getRecordedByMembershipId()),
        List.of(),
        offset(approval.getRevokedAt()),
        approval.getRevocationReason(),
        offset(approval.getRevocationRecordedAt()),
        approval.getRevokedByUserId());
  }

  private static OffsetDateTime offset(Instant instant) {
    return instant == null ? null : TimeRules.toDisplayOffset(instant);
  }

  private int retentionDays() {
    return retentionRules
        .retentionDays(RETENTION_RULE)
        .orElseThrow(() -> new IllegalStateException("승인자 개인정보 보관 규칙이 없습니다."));
  }

  private record Input(
      ApproverType type,
      String name,
      String phone,
      Instant validFrom,
      Instant validUntil,
      InputSource source,
      ApprovalRole role,
      Instant confirmedAt) {}

  private Input parse(JsonNode body) {
    CustomerInput.requireObject(body, BODY_KEYS);
    ApproverType type = CustomerInput.requiredEnum(body, "approverType", ApproverType.class);
    String name = CustomerInput.requiredText(body, "approverName", 50, false);
    InputSource source = CustomerInput.requiredEnum(body, "source", InputSource.class);
    ApprovalRole role =
        CustomerInput.isNull(body, "approvalRole")
            ? ApprovalRole.PRIMARY
            : CustomerInput.enumValue(body, "approvalRole", ApprovalRole.class);
    OffsetDateTime validFrom = CustomerInput.requiredDateTime(body, "validFrom");
    OffsetDateTime validUntil = CustomerInput.requiredDateTime(body, "validUntil");
    if (!validUntil.isAfter(validFrom)) {
      throw CustomerInput.invalid(
          "validUntil", FieldErrorCode.INVALID_VALUE, "유효 종료는 유효 시작보다 늦어야 합니다.");
    }
    String phone = null;
    String rawPhone = CustomerInput.optionalText(body, "approverPhone", 30, false);
    if (rawPhone != null) {
      phone =
          PhoneNumbers.normalize(rawPhone)
              .orElseThrow(
                  () ->
                      CustomerInput.invalid(
                          "approverPhone", FieldErrorCode.INVALID_FORMAT, "휴대폰 번호 형식이어야 합니다."));
    }
    if (source == InputSource.DIRECT && phone == null) {
      throw CustomerInput.invalid("approverPhone", FieldErrorCode.REQUIRED, "직접 입력은 연락처가 필수입니다.");
    }
    validateTimeSlots(body);
    Instant confirmedAt = null;
    if (source == InputSource.PROXY) {
      OffsetDateTime confirmed = CustomerInput.requiredDateTime(body, "confirmedAt");
      if (confirmed.toInstant().isAfter(clock.instant())) {
        throw CustomerInput.invalid(
            "confirmedAt", FieldErrorCode.OUT_OF_RANGE, "확인 시각은 현재 이후일 수 없습니다.");
      }
      confirmedAt = confirmed.toInstant();
    } else if (!CustomerInput.isNull(body, "confirmedAt")) {
      throw CustomerInput.invalid(
          "confirmedAt", FieldErrorCode.NOT_ALLOWED, "대리 입력일 때만 보낼 수 있습니다.");
    }
    return new Input(
        type,
        name,
        phone,
        validFrom.toInstant(),
        validUntil.toInstant(),
        source,
        role,
        confirmedAt);
  }

  private static void validateTimeSlots(JsonNode body) {
    if (CustomerInput.isNull(body, "timeSlots")) {
      return;
    }
    if (!body.get("timeSlots").isArray()) {
      throw CustomerInput.invalid("timeSlots", FieldErrorCode.TYPE_MISMATCH, "배열이어야 합니다.");
    }
  }
}
