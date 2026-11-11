package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.property.service.PropertyReferenceService;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.ApproverConditionBulkApplyResponse;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.ApproverConditionBulkPreviewResponse;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.BulkApplyItem;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.BulkPreviewItem;
import com.sairo.be.domain.visit.dto.response.ApproverConditionImpactPreviewResponse.AffectedItem;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.repository.ApproverProfileRepository;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.domain.visit.service.ApproverConditionParser.Mode;
import com.sairo.be.domain.visit.service.ApproverConditionParser.Parsed;
import com.sairo.be.domain.visit.service.ApproverConditionService.BulkItemOutcome;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** 승인자 계정의 여러 출입승인 조건 일괄 미리보기와 적용이다(RESPOND-10). */
@Profile("!migrate")
@Service
public class ApproverConditionBulkService {

  public static final int MAX_APPROVALS = 20;
  private static final Set<String> PREVIEW_KEYS = Set.of("approvalIds", "condition");
  private static final Set<String> APPLY_KEYS =
      Set.of(
          "approvalIds", "condition", "impactAcknowledgedApprovalIds", "acknowledgedNoticeVersion");

  private final ApproverConditionService conditionService;
  private final ApproverAccountService accountService;
  private final ApproverProfileRepository profileRepository;
  private final PropertyAccessApprovalRepository approvalRepository;
  private final PropertyReferenceService propertyReferences;
  private final ApproverLinkNoticeProvider noticeProvider;
  private final Clock clock;

  public ApproverConditionBulkService(
      ApproverConditionService conditionService,
      ApproverAccountService accountService,
      ApproverProfileRepository profileRepository,
      PropertyAccessApprovalRepository approvalRepository,
      PropertyReferenceService propertyReferences,
      ApproverLinkNoticeProvider noticeProvider,
      Clock clock) {
    this.conditionService = conditionService;
    this.accountService = accountService;
    this.profileRepository = profileRepository;
    this.approvalRepository = approvalRepository;
    this.propertyReferences = propertyReferences;
    this.noticeProvider = noticeProvider;
    this.clock = clock;
  }

  public ApproverConditionBulkPreviewResponse preview(Long profileId, JsonNode body) {
    CustomerInput.requireObject(body, PREVIEW_KEYS);
    List<Long> approvalIds = approvalIds(body, "approvalIds", true);
    Parsed parsed = condition(body);
    accountService.activeProfile(profileId);
    Instant now = clock.instant();
    List<BulkPreviewItem> items = new ArrayList<>();
    for (Long approvalId : approvalIds) {
      boolean linked = profileRepository.isLinked(profileId, approvalId);
      Optional<PropertyAccessApproval> approval =
          linked ? approvalRepository.findById(approvalId) : Optional.empty();
      String reason = null;
      if (approval.isEmpty()) {
        reason = "NOT_LINKED";
      } else if (!approval.get().isPrimary()) {
        reason = "NOT_PRIMARY";
      } else if (!approval.get().isUsableAt(now)) {
        reason = "APPROVAL_INACTIVE";
      }
      List<AffectedItem> affected =
          reason == null ? conditionService.affectedFor(approvalId, parsed.spec()) : List.of();
      items.add(
          new BulkPreviewItem(
              approvalId,
              approval
                  .flatMap(found -> propertyReferences.find(found.getPropertyId()))
                  .map(PropertyReferenceService.PropertySummary::name)
                  .orElse(null),
              reason == null,
              reason,
              !affected.isEmpty(),
              affected));
    }
    return new ApproverConditionBulkPreviewResponse(items);
  }

  public ApproverConditionBulkApplyResponse apply(Long profileId, JsonNode body, String clientIp) {
    CustomerInput.requireObject(body, APPLY_KEYS);
    List<Long> approvalIds = approvalIds(body, "approvalIds", true);
    Parsed parsed = condition(body);
    List<Long> acknowledged =
        CustomerInput.isNull(body, "impactAcknowledgedApprovalIds")
            ? List.of()
            : approvalIds(body, "impactAcknowledgedApprovalIds", false);
    if (!approvalIds.containsAll(acknowledged)) {
      throw CustomerInput.invalid(
          "impactAcknowledgedApprovalIds",
          FieldErrorCode.INVALID_VALUE,
          "일괄 적용 대상 출입승인에 있는 번호만 보낼 수 있습니다.");
    }
    String noticeVersion = CustomerInput.requiredText(body, "acknowledgedNoticeVersion", 50, false);
    ApproverLinkNoticeProvider.Notice notice = noticeProvider.current();
    if (!notice.noticeVersion().equals(noticeVersion)) {
      throw new BusinessException(ErrorCode.NOTICE_VERSION_CHANGED);
    }
    accountService.activeProfile(profileId);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Long batchId = profileRepository.insertBatch(profileId, approvalIds.size(), now);
    int applied = 0;
    int needsAck = 0;
    int failed = 0;
    List<BulkApplyItem> items = new ArrayList<>();
    for (Long approvalId : approvalIds) {
      BulkItemOutcome outcome;
      try {
        outcome =
            conditionService.applyBulkItem(
                profileId, approvalId, parsed, acknowledged.contains(approvalId), notice);
      } catch (BusinessException e) {
        outcome = new BulkItemOutcome("FAILED", null, null, "INVALID_CONDITION", 0);
      }
      switch (outcome.result()) {
        case "APPLIED" -> applied++;
        case "NEEDS_IMPACT_ACK" -> needsAck++;
        default -> failed++;
      }
      if (approvalRepository.existsById(approvalId)) {
        profileRepository.insertBatchItem(
            batchId,
            approvalId,
            outcome.result(),
            outcome.conditionId(),
            outcome.failureCode(),
            outcome.affectedItemCount());
      }
      items.add(
          new BulkApplyItem(
              approvalId,
              outcome.result(),
              outcome.conditionId(),
              outcome.versionNo(),
              outcome.failureCode(),
              outcome.affectedItemCount()));
    }
    profileRepository.completeBatch(batchId, applied, needsAck, failed, now);
    profileRepository.recordEvent(
        profileId, null, batchId, "BULK_APPLIED", null, accountService.ipHmac(clientIp), now);
    return new ApproverConditionBulkApplyResponse(
        batchId, approvalIds.size(), applied, needsAck, failed, items);
  }

  private static Parsed condition(JsonNode body) {
    JsonNode condition = body.get("condition");
    if (condition == null || !condition.isObject()) {
      throw CustomerInput.invalid("condition", FieldErrorCode.REQUIRED, "적용할 조건이 필요합니다.");
    }
    return ApproverConditionParser.parse(condition, Mode.BULK);
  }

  private static List<Long> approvalIds(JsonNode body, String field, boolean required) {
    JsonNode ids = body.get(field);
    if (ids == null || ids.isNull() || !ids.isArray() || (required && ids.isEmpty())) {
      throw CustomerInput.invalid(field, FieldErrorCode.REQUIRED, "출입승인 번호가 1개 이상 필요합니다.");
    }
    if (ids.size() > MAX_APPROVALS) {
      throw new BusinessException(ErrorCode.BULK_LIMIT_EXCEEDED);
    }
    Set<Long> seen = new LinkedHashSet<>();
    for (JsonNode id : ids) {
      if (!id.isIntegralNumber() || id.asLong() < 1 || !seen.add(id.asLong())) {
        throw CustomerInput.invalid(
            field, FieldErrorCode.INVALID_VALUE, "출입승인 번호가 올바르지 않거나 중복되었습니다.");
      }
    }
    return List.copyOf(seen);
  }
}
