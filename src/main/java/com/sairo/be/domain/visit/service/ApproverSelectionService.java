package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.visit.dto.response.ApproverSelectionResponse;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.domain.visit.repository.VisitApproverPropertyLinkRepository.LinkHead;
import com.sairo.be.domain.visit.service.ApproverSelectionExecutor.Input;
import com.sairo.be.domain.visit.service.ApproverSelectionExecutor.Result;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.support.IfMatchVersion;
import com.sairo.be.global.support.RequestRateLimiter;
import java.util.HashSet;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class ApproverSelectionService {

  private static final Set<String> KEYS =
      Set.of("comparedRequestIds", "selectionNote", "acknowledgedNoticeVersion");
  private static final int NOTE_MAX = 500;
  private static final int MAX_ATTEMPTS = 3;
  private static final int COMPARED_MAX = 200;

  private final PublicApproverLinkResolver linkResolver;
  private final ApproverLinkAccessRecorder accessRecorder;
  private final PropertyAccessApprovalRepository approvalRepository;
  private final ApproverLinkNoticeProvider noticeProvider;
  private final ApproverSelectionExecutor executor;
  private final RequestRateLimiter rateLimiter;

  public ApproverSelectionService(
      PublicApproverLinkResolver linkResolver,
      ApproverLinkAccessRecorder accessRecorder,
      PropertyAccessApprovalRepository approvalRepository,
      ApproverLinkNoticeProvider noticeProvider,
      ApproverSelectionExecutor executor,
      RequestRateLimiter rateLimiter) {
    this.linkResolver = linkResolver;
    this.accessRecorder = accessRecorder;
    this.approvalRepository = approvalRepository;
    this.noticeProvider = noticeProvider;
    this.executor = executor;
    this.rateLimiter = rateLimiter;
  }

  public ApproverSelectionResponse select(
      String token,
      Long requestId,
      String ifMatch,
      String idempotencyKey,
      JsonNode body,
      String clientIp) {
    LinkHead head = linkResolver.resolve(token, clientIp);
    accessRecorder.record(head.getId());
    PropertyAccessApproval approval =
        approvalRepository
            .findById(head.getApprovalId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    if (!approval.isPrimary()) {
      throw new BusinessException(ErrorCode.APPROVER_READ_ONLY);
    }
    rateLimiter.hit(VisitRateLimits.APPROVER_SELECT_APPROVAL, String.valueOf(approval.getId()));
    Parsed parsed = parse(body, requestId);
    long expectedVersion = IfMatchVersion.parse(ifMatch);
    String current = noticeProvider.current().noticeVersion();
    if (!current.equals(parsed.noticeVersion())) {
      throw new BusinessException(ErrorCode.NOTICE_VERSION_CHANGED);
    }
    Input input =
        new Input(
            approval.getId(),
            head.getId(),
            requestId,
            expectedVersion,
            idempotencyKey != null && !idempotencyKey.isBlank(),
            parsed.comparedCount(),
            parsed.note());
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      Result result = executor.execute(input);
      if (result.retry()) {
        continue;
      }
      if (result.rejection() != null) {
        throw new BusinessException(result.rejection());
      }
      return result.response();
    }
    throw new BusinessException(ErrorCode.VERSION_CONFLICT);
  }

  private record Parsed(int comparedCount, String note, String noticeVersion) {}

  private static Parsed parse(JsonNode body, Long requestId) {
    CustomerInput.requireObject(body, KEYS);
    JsonNode ids = body.get("comparedRequestIds");
    if (ids == null || !ids.isArray() || ids.isEmpty() || ids.size() > COMPARED_MAX) {
      throw CustomerInput.invalid(
          "comparedRequestIds", FieldErrorCode.REQUIRED, "비교한 신청 번호가 1개 이상 필요합니다.");
    }
    Set<Long> seen = new HashSet<>();
    for (JsonNode id : ids) {
      if (!id.isIntegralNumber() || id.asLong() < 1 || !seen.add(id.asLong())) {
        throw CustomerInput.invalid(
            "comparedRequestIds", FieldErrorCode.INVALID_VALUE, "신청 번호가 올바르지 않거나 중복되었습니다.");
      }
    }
    if (!seen.contains(requestId)) {
      throw CustomerInput.invalid(
          "comparedRequestIds", FieldErrorCode.INVALID_VALUE, "선택한 신청이 비교 목록에 있어야 합니다.");
    }
    String note = CustomerInput.optionalText(body, "selectionNote", NOTE_MAX, false);
    String version = CustomerInput.requiredText(body, "acknowledgedNoticeVersion", 50, false);
    return new Parsed(seen.size(), note, version);
  }
}
