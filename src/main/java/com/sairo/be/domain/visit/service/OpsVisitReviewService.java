package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.OpsDisputeItem;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.OpsDisputedReview;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.OpsReviewedBroker;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.OpsVisitReviewResponse;
import com.sairo.be.domain.visit.repository.BusinessDayRepository;
import com.sairo.be.domain.visit.repository.MembershipNameRepository;
import com.sairo.be.domain.visit.repository.VisitReviewRepository;
import com.sairo.be.domain.visit.repository.VisitReviewRepository.Dispute;
import com.sairo.be.domain.visit.repository.VisitReviewRepository.OpsRow;
import com.sairo.be.domain.visit.repository.VisitReviewRepository.Review;
import com.sairo.be.global.config.VisitReviewProperties;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.OfficeDirectory;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class OpsVisitReviewService {

  static final String TARGET_TYPE = "VISIT_REVIEW";
  private static final Set<String> DISPUTE_STATUSES = Set.of("OPEN", "UPHELD", "REMOVED");
  private static final Set<String> DECISIONS = Set.of("UPHELD", "REMOVED");
  private static final Set<String> REMOVE_REASONS =
      Set.of("LEGAL_REQUEST", "PRIVACY_REQUEST", "OPERATOR_DISCRETION");

  private final VisitReviewRepository reviewRepository;
  private final MembershipNameRepository nameRepository;
  private final OfficeDirectory officeDirectory;
  private final BusinessDayRepository businessDays;
  private final OpsAuditLogRepository auditLogRepository;
  private final VisitReviewProperties reviewPolicy;
  private final Clock clock;

  public OpsVisitReviewService(
      VisitReviewRepository reviewRepository,
      MembershipNameRepository nameRepository,
      OfficeDirectory officeDirectory,
      BusinessDayRepository businessDays,
      OpsAuditLogRepository auditLogRepository,
      VisitReviewProperties reviewPolicy,
      Clock clock) {
    this.reviewRepository = reviewRepository;
    this.nameRepository = nameRepository;
    this.officeDirectory = officeDirectory;
    this.businessDays = businessDays;
    this.auditLogRepository = auditLogRepository;
    this.reviewPolicy = reviewPolicy;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<OpsDisputeItem> list(String status, PageQuery page) {
    Set<String> statuses = parseStatuses(status);
    List<OpsRow> rows =
        reviewRepository.opsDisputes(statuses, page.size(), (long) page.page() * page.size());
    long total = reviewRepository.countOpsDisputes(statuses);
    Map<Long, String> names =
        nameRepository.displayNames(
            rows.stream().map(row -> row.review().reviewedMembershipId()).distinct().toList());
    Map<Long, String> offices = new HashMap<>();
    List<OpsDisputeItem> items =
        rows.stream()
            .map(
                row -> {
                  Dispute dispute = row.dispute();
                  Review review = row.review();
                  return new OpsDisputeItem(
                      dispute.id(),
                      dispute.status(),
                      dispute.reasonCode(),
                      dispute.reasonText(),
                      TimeRules.toDisplayOffset(dispute.filedAt()),
                      TimeRules.toDisplayOffset(
                          businessDays.dueAfter(
                              dispute.filedAt(),
                              reviewPolicy.disputeTargetBusinessDays(),
                              reviewPolicy.disputeDueTime())),
                      new OpsDisputedReview(
                          review.id(),
                          review.itemId(),
                          review.status(),
                          TimeRules.toDisplayOffset(row.actualVisitedAt()),
                          ApproverReviewService.answers(review),
                          TimeRules.toDisplayOffset(review.submittedAt()),
                          TimeRules.toDisplayOffset(review.lastRevisedAt()),
                          review.revisionCount(),
                          row.approverType(),
                          review.reviewedMembershipId().equals(row.issuedByMembershipId())),
                      new OpsReviewedBroker(
                          review.reviewedMembershipId(),
                          names.getOrDefault(review.reviewedMembershipId(), ""),
                          review.reviewedOfficeId(),
                          offices.computeIfAbsent(review.reviewedOfficeId(), this::officeName)),
                      TimeRules.toDisplayOffset(dispute.decidedAt()),
                      dispute.decisionNote());
                })
            .toList();
    return PageResponse.of(items, page.page(), page.size(), total);
  }

  @Transactional
  public OpsVisitReviewResponse decide(long operatorId, Long disputeId, JsonNode body) {
    CustomerInput.requireObject(body, Set.of("decision", "decisionNote"));
    String decision = CustomerInput.requiredText(body, "decision", 10, false);
    if (!DECISIONS.contains(decision)) {
      throw CustomerInput.invalid(
          "decision", FieldErrorCode.NOT_ALLOWED, "UPHELD 또는 REMOVED여야 합니다.");
    }
    String note = CustomerInput.requiredText(body, "decisionNote", 500, false);
    Dispute preview =
        reviewRepository
            .findDispute(disputeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Review review =
        reviewRepository
            .lock(preview.reviewId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Dispute dispute =
        reviewRepository
            .lockDispute(disputeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!"OPEN".equals(dispute.status())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = now();
    reviewRepository.decideDispute(dispute.id(), decision, operatorId, note, now);
    if ("REMOVED".equals(decision)) {
      reviewRepository.remove(review.id(), "DISPUTE_ACCEPTED", operatorId, now);
    } else {
      reviewRepository.markStatus(review.id(), "ACTIVE", now);
    }
    auditLogRepository.insert(
        operatorId,
        "VISIT_REVIEW_DISPUTE_DECIDED",
        TARGET_TYPE,
        review.id(),
        "decision=" + decision + ";reasonCode=" + dispute.reasonCode(),
        now);
    return new OpsVisitReviewResponse(
        review.id(),
        "REMOVED".equals(decision) ? "REMOVED" : "ACTIVE",
        dispute.id(),
        decision,
        "REMOVED".equals(decision) ? TimeRules.toDisplayOffset(now) : null,
        TimeRules.toDisplayOffset(now));
  }

  @Transactional
  public OpsVisitReviewResponse remove(long operatorId, Long reviewId, JsonNode body) {
    CustomerInput.requireObject(body, Set.of("reasonCode", "decisionNote"));
    String reasonCode = CustomerInput.requiredText(body, "reasonCode", 30, false);
    if (!REMOVE_REASONS.contains(reasonCode)) {
      throw CustomerInput.invalid(
          "reasonCode",
          FieldErrorCode.NOT_ALLOWED,
          "LEGAL_REQUEST, PRIVACY_REQUEST, OPERATOR_DISCRETION 중 하나여야 합니다.");
    }
    String note = CustomerInput.requiredText(body, "decisionNote", 500, false);
    Review review =
        reviewRepository
            .lock(reviewId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!"ACTIVE".equals(review.status()) && !"DISPUTED".equals(review.status())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = now();
    Optional<Dispute> open =
        reviewRepository
            .disputeOf(review.id())
            .filter(found -> "OPEN".equals(found.status()))
            .flatMap(found -> reviewRepository.lockDispute(found.id()));
    open.ifPresent(
        dispute -> reviewRepository.decideDispute(dispute.id(), "REMOVED", operatorId, note, now));
    reviewRepository.remove(review.id(), reasonCode, operatorId, now);
    auditLogRepository.insert(
        operatorId,
        "VISIT_REVIEW_REMOVED",
        TARGET_TYPE,
        review.id(),
        "reasonCode=" + reasonCode,
        now);
    return new OpsVisitReviewResponse(
        review.id(),
        "REMOVED",
        open.map(Dispute::id).orElse(null),
        open.map(dispute -> "REMOVED").orElse(null),
        TimeRules.toDisplayOffset(now),
        TimeRules.toDisplayOffset(now));
  }

  private static Set<String> parseStatuses(String raw) {
    if (raw == null || raw.isBlank()) {
      return Set.of("OPEN");
    }
    Set<String> parsed = new LinkedHashSet<>();
    for (String token : raw.split(",")) {
      String value = token.strip();
      if (!DISPUTE_STATUSES.contains(value)) {
        throw CustomerInput.invalid("status", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 status 값입니다.");
      }
      parsed.add(value);
    }
    return parsed;
  }

  private String officeName(Long officeId) {
    return officeDirectory.find(officeId).map(OfficeDirectory.OfficeSummary::name).orElse("");
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
