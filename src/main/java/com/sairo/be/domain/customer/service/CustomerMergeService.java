package com.sairo.be.domain.customer.service;

import static com.sairo.be.domain.customer.service.CustomerInput.has;

import com.sairo.be.domain.customer.dto.response.CustomerMergeResponse;
import com.sairo.be.domain.customer.entity.ConsultationType;
import com.sairo.be.domain.customer.entity.Customer;
import com.sairo.be.domain.customer.entity.CustomerConsultation;
import com.sairo.be.domain.customer.entity.CustomerConsultationRevision;
import com.sairo.be.domain.customer.entity.CustomerMergeLog;
import com.sairo.be.domain.customer.repository.CustomerConsultationRepository;
import com.sairo.be.domain.customer.repository.CustomerConsultationRevisionRepository;
import com.sairo.be.domain.customer.repository.CustomerMergeLogRepository;
import com.sairo.be.domain.customer.repository.CustomerReadRepository;
import com.sairo.be.domain.customer.repository.CustomerRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class CustomerMergeService {

  private static final Set<String> KEYS =
      Set.of(
          "primaryCustomerId",
          "mergedCustomerId",
          "conflictResolutionNote",
          "keepFirstInquiryId",
          "mergedCustomerVersion",
          "fieldChoices");
  private static final Set<String> CHOICE_KEYS =
      Set.of("name", "phone", "secondaryPhone", "email", "memo", "managingMembershipId");
  private static final String RECLASSIFY_REASON = "고객 병합으로 최초문의를 후속 상담으로 재분류";

  private final CustomerRepository customerRepository;
  private final CustomerMergeLogRepository mergeLogRepository;
  private final CustomerConsultationRepository consultationRepository;
  private final CustomerConsultationRevisionRepository revisionRepository;
  private final CustomerReadRepository readRepository;
  private final CustomerGuard guard;

  public CustomerMergeService(
      CustomerRepository customerRepository,
      CustomerMergeLogRepository mergeLogRepository,
      CustomerConsultationRepository consultationRepository,
      CustomerConsultationRevisionRepository revisionRepository,
      CustomerReadRepository readRepository,
      CustomerGuard guard) {
    this.customerRepository = customerRepository;
    this.mergeLogRepository = mergeLogRepository;
    this.consultationRepository = consultationRepository;
    this.revisionRepository = revisionRepository;
    this.readRepository = readRepository;
    this.guard = guard;
  }

  @Transactional
  public CustomerMergeResponse merge(
      StaffPrincipal principal, long primaryExpectedVersion, JsonNode body) {
    CustomerInput.requireObject(body, KEYS);
    Long primaryId = CustomerInput.requiredLong(body, "primaryCustomerId");
    Long mergedId = CustomerInput.requiredLong(body, "mergedCustomerId");
    String note = CustomerInput.requiredText(body, "conflictResolutionNote", 2_000, false);
    Long mergedVersion = CustomerInput.requiredLong(body, "mergedCustomerVersion");
    if (mergedVersion < 1) {
      throw CustomerInput.invalid(
          "mergedCustomerVersion", FieldErrorCode.OUT_OF_RANGE, "1 이상이어야 합니다.");
    }
    Long keepFirstInquiryId = CustomerInput.optionalLong(body, "keepFirstInquiryId");
    Set<String> merging = choices(body);
    if (primaryId.equals(mergedId)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    guard.find(principal.officeId(), primaryId);
    guard.find(principal.officeId(), mergedId);
    Customer primary;
    Customer merged;
    if (primaryId < mergedId) {
      primary = guard.lock(principal.officeId(), primaryId);
      merged = guard.lock(principal.officeId(), mergedId);
    } else {
      merged = guard.lock(principal.officeId(), mergedId);
      primary = guard.lock(principal.officeId(), primaryId);
    }
    if (readRepository.isMerged(primaryId) || readRepository.isMerged(mergedId)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    guard.requireNotArchived(primary);
    guard.requireNotArchived(merged);
    guard.requireVersion(primary, primaryExpectedVersion);
    if (merged.getVersion() != mergedVersion) {
      throw BusinessException.versionConflict(merged.getVersion());
    }
    List<Long> primarySet = readRepository.mergedSet(primaryId);
    List<Long> mergedSet = readRepository.mergedSet(mergedId);
    reclassifyFirstInquiries(principal, primarySet, mergedSet, keepFirstInquiryId);
    if (readRepository.hasScheduleOverlap(primarySet, mergedSet)
        || readRepository.hasScheduleOverlap(mergedSet, primarySet)) {
      throw new BusinessException(ErrorCode.SCHEDULE_CONFLICT);
    }
    adopt(primary, merged, merging);
    primary.touch();
    merged.touch();
    customerRepository.saveAndFlush(merged);
    Customer savedPrimary = customerRepository.saveAndFlush(primary);
    CustomerMergeLog log =
        mergeLogRepository.saveAndFlush(
            CustomerMergeLog.record(primaryId, mergedId, principal.membershipId(), note));
    return new CustomerMergeResponse(
        primaryId,
        mergedId,
        TimeRules.toDisplayOffset(log.getExecutedAt()),
        savedPrimary.getVersion());
  }

  private void reclassifyFirstInquiries(
      StaffPrincipal principal, List<Long> primarySet, List<Long> mergedSet, Long keepId) {
    List<Long> primaryFirst = readRepository.firstInquiryIds(primarySet);
    List<Long> mergedFirst = readRepository.firstInquiryIds(mergedSet);
    List<Long> all = new ArrayList<>(primaryFirst);
    all.addAll(mergedFirst);
    if (keepId != null && !all.contains(keepId)) {
      throw CustomerInput.invalid(
          "keepFirstInquiryId", FieldErrorCode.NOT_ALLOWED, "병합 대상 고객의 최초문의가 아닙니다.");
    }
    if (primaryFirst.isEmpty() || mergedFirst.isEmpty()) {
      return;
    }
    if (keepId == null) {
      throw CustomerInput.invalid(
          "keepFirstInquiryId", FieldErrorCode.REQUIRED, "두 고객 모두 최초문의가 있어 유지할 상담을 골라야 합니다.");
    }
    for (Long consultationId : all) {
      if (consultationId.equals(keepId)) {
        continue;
      }
      CustomerConsultation consultation =
          consultationRepository.findById(consultationId).orElseThrow();
      revisionRepository.save(
          CustomerConsultationRevision.preserve(
              consultation, RECLASSIFY_REASON, principal.membershipId()));
      consultation.correct(
          consultation.getOccurredAt(), consultation.getContent(), ConsultationType.FOLLOWUP);
      consultationRepository.saveAndFlush(consultation);
    }
  }

  private static void adopt(Customer primary, Customer merged, Set<String> merging) {
    if (merging.contains("name")) {
      primary.changeName(merged.getName());
    }
    if (merging.contains("phone")
        || merging.contains("secondaryPhone")
        || merging.contains("email")) {
      primary.changeContact(
          merging.contains("phone") ? merged.getPhone() : primary.getPhone(),
          merging.contains("secondaryPhone")
              ? merged.getSecondaryPhone()
              : primary.getSecondaryPhone(),
          merging.contains("email") ? merged.getEmail() : primary.getEmail());
    }
    if (merging.contains("memo")) {
      primary.changeMemo(merged.getMemo());
    }
    if (merging.contains("managingMembershipId")) {
      primary.changeManager(merged.getManagingMembershipId());
    }
  }

  private static Set<String> choices(JsonNode body) {
    Set<String> merging = new java.util.HashSet<>();
    if (!has(body, "fieldChoices") || body.get("fieldChoices").isNull()) {
      return merging;
    }
    JsonNode node = body.get("fieldChoices");
    if (!node.isObject()) {
      throw CustomerInput.invalid("fieldChoices", FieldErrorCode.TYPE_MISMATCH, "객체여야 합니다.");
    }
    for (String name : node.propertyNames()) {
      if (!CHOICE_KEYS.contains(name)) {
        throw CustomerInput.invalid(
            "fieldChoices." + name, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 필드입니다.");
      }
      JsonNode value = node.get(name);
      String side = value.isString() ? value.asString() : "";
      if (side.equals("MERGED")) {
        merging.add(name);
      } else if (!side.equals("PRIMARY")) {
        throw CustomerInput.invalid(
            "fieldChoices." + name, FieldErrorCode.NOT_ALLOWED, "PRIMARY 또는 MERGED여야 합니다.");
      }
    }
    return merging;
  }
}
