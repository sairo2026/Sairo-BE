package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.property.service.PropertyReferenceService;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.BrokerVisitTrustSummaryResponse;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.OwnTrustIndicator;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ReceivedDispute;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ReceivedReviewItem;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.VisitReviewDisputeResponse;
import com.sairo.be.domain.visit.repository.BusinessDayRepository;
import com.sairo.be.domain.visit.repository.MembershipNameRepository;
import com.sairo.be.domain.visit.repository.TrustSummaryRepository;
import com.sairo.be.domain.visit.repository.VisitReviewRepository;
import com.sairo.be.domain.visit.repository.VisitReviewRepository.Dispute;
import com.sairo.be.domain.visit.repository.VisitReviewRepository.ReceivedRow;
import com.sairo.be.domain.visit.repository.VisitReviewRepository.Review;
import com.sairo.be.global.config.VisitReviewProperties;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
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
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class VisitTrustService {

  private static final Set<String> RECEIVED_STATUSES = Set.of("ACTIVE", "DISPUTED", "REMOVED");
  private static final Set<String> DISPUTE_KEYS = Set.of("reasonCode", "reasonText");
  private static final Set<String> DISPUTE_REASONS =
      Set.of("FACT_INCORRECT", "WRONG_TARGET", "OTHER");

  private final TrustSummaryRepository trustRepository;
  private final VisitReviewRepository reviewRepository;
  private final MembershipNameRepository nameRepository;
  private final PropertyReferenceService propertyReferences;
  private final BusinessDayRepository businessDays;
  private final VisitReviewProperties reviewPolicy;
  private final Clock clock;

  public VisitTrustService(
      TrustSummaryRepository trustRepository,
      VisitReviewRepository reviewRepository,
      MembershipNameRepository nameRepository,
      PropertyReferenceService propertyReferences,
      BusinessDayRepository businessDays,
      VisitReviewProperties reviewPolicy,
      Clock clock) {
    this.trustRepository = trustRepository;
    this.reviewRepository = reviewRepository;
    this.nameRepository = nameRepository;
    this.propertyReferences = propertyReferences;
    this.businessDays = businessDays;
    this.reviewPolicy = reviewPolicy;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public BrokerVisitTrustSummaryResponse me(StaffPrincipal principal) {
    requireAgentOrOwner(principal);
    Instant now = now();
    TrustSummary summary = trustRepository.summarize(principal.membershipId(), now);
    return new BrokerVisitTrustSummaryResponse(
        TrustSummary.POLICY_VERSION,
        TimeRules.toDisplayOffset(now),
        TrustSummary.WINDOW_MONTHS,
        summary.state(),
        summary.overallBand(),
        summary.completedVisitCount(),
        summary.evaluatedVisitCount(),
        summary.distinctApproverCount(),
        trustRepository.excludedCount(principal.membershipId(), now),
        summary.indicators().stream()
            .map(
                indicator ->
                    new OwnTrustIndicator(
                        indicator.indicator(),
                        indicator.answeredCount(),
                        indicator.yesCount(),
                        indicator.band()))
            .toList(),
        ApproverRequestCompareService.UNCONFIRMED_INDICATORS);
  }

  @Transactional(readOnly = true)
  public PageResponse<ReceivedReviewItem> received(
      StaffPrincipal principal, String scope, String status, PageQuery page) {
    requireAgentOrOwner(principal);
    boolean office = parseScope(scope);
    if (office && principal.role() != StaffRole.OWNER) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    Set<String> statuses = parseStatuses(status);
    Long member = office ? null : principal.membershipId();
    List<ReceivedRow> rows =
        reviewRepository.received(
            principal.officeId(), member, statuses, page.size(), (long) page.page() * page.size());
    long total = reviewRepository.countReceived(principal.officeId(), member, statuses);
    Map<Long, String> names =
        nameRepository.displayNames(
            rows.stream().map(row -> row.review().reviewedMembershipId()).distinct().toList());
    Map<Long, String> properties = new HashMap<>();
    Instant now = now();
    List<ReceivedReviewItem> items =
        rows.stream()
            .map(
                row -> {
                  Review review = row.review();
                  Dispute dispute = reviewRepository.disputeOf(review.id()).orElse(null);
                  boolean canDispute =
                      "ACTIVE".equals(review.status())
                          && dispute == null
                          && now.isBefore(review.lastRevisedAt().plus(reviewPolicy.disputeWindow()))
                          && (review.reviewedMembershipId().equals(principal.membershipId())
                              || principal.role() == StaffRole.OWNER);
                  return new ReceivedReviewItem(
                      review.id(),
                      review.itemId(),
                      TimeRules.toDisplayOffset(row.actualVisitedAt()),
                      properties.computeIfAbsent(row.propertyId(), this::propertyName),
                      names.getOrDefault(review.reviewedMembershipId(), ""),
                      review.status(),
                      ApproverReviewService.answers(review),
                      TimeRules.toDisplayOffset(review.submittedAt()),
                      TimeRules.toDisplayOffset(review.lastRevisedAt()),
                      canDispute,
                      dispute == null
                          ? null
                          : new ReceivedDispute(
                              dispute.id(),
                              dispute.status(),
                              TimeRules.toDisplayOffset(dispute.filedAt()),
                              TimeRules.toDisplayOffset(dispute.decidedAt()),
                              dispute.decisionNote()));
                })
            .toList();
    return PageResponse.of(items, page.page(), page.size(), total);
  }

  @Transactional
  public VisitReviewDisputeResponse dispute(
      StaffPrincipal principal, Long reviewId, JsonNode body) {
    requireAgentOrOwner(principal);
    CustomerInput.requireObject(body, DISPUTE_KEYS);
    String reasonCode = CustomerInput.requiredText(body, "reasonCode", 20, false);
    if (!DISPUTE_REASONS.contains(reasonCode)) {
      throw CustomerInput.invalid(
          "reasonCode",
          FieldErrorCode.NOT_ALLOWED,
          "FACT_INCORRECT, WRONG_TARGET, OTHER 중 하나여야 합니다.");
    }
    String reasonText = CustomerInput.requiredText(body, "reasonText", 500, true);
    Review review =
        reviewRepository
            .lock(reviewId)
            .filter(found -> found.reviewedOfficeId().equals(principal.officeId()))
            .filter(
                found ->
                    found.reviewedMembershipId().equals(principal.membershipId())
                        || principal.role() == StaffRole.OWNER)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (review.disputed()) {
      throw new BusinessException(ErrorCode.VISIT_REVIEW_DISPUTE_EXISTS);
    }
    if (!"ACTIVE".equals(review.status())) {
      throw new BusinessException(ErrorCode.VISIT_REVIEW_LOCKED);
    }
    Instant now = now();
    if (!now.isBefore(review.lastRevisedAt().plus(reviewPolicy.disputeWindow()))) {
      throw new BusinessException(ErrorCode.VISIT_REVIEW_DISPUTE_WINDOW_CLOSED);
    }
    Long disputeId =
        reviewRepository.insertDispute(
            review, principal.membershipId(), reasonCode, reasonText, now);
    reviewRepository.markStatus(review.id(), "DISPUTED", now);
    return new VisitReviewDisputeResponse(
        disputeId,
        review.id(),
        "OPEN",
        reasonCode,
        TimeRules.toDisplayOffset(now),
        TimeRules.toDisplayOffset(
            businessDays.dueAfter(
                now, reviewPolicy.disputeTargetBusinessDays(), reviewPolicy.disputeDueTime())),
        "DISPUTED");
  }

  private static void requireAgentOrOwner(StaffPrincipal principal) {
    if (principal.role() == StaffRole.DEPUTY) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  private static boolean parseScope(String scope) {
    if (scope == null || scope.isBlank() || "MINE".equals(scope)) {
      return false;
    }
    if ("OFFICE".equals(scope)) {
      return true;
    }
    throw BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST,
        List.of(new FieldErrorItem("scope", FieldErrorCode.NOT_ALLOWED, "MINE 또는 OFFICE여야 합니다.")));
  }

  private static Set<String> parseStatuses(String raw) {
    if (raw == null || raw.isBlank()) {
      return RECEIVED_STATUSES;
    }
    Set<String> parsed = new LinkedHashSet<>();
    for (String token : raw.split(",")) {
      String value = token.strip();
      if (!RECEIVED_STATUSES.contains(value)) {
        throw BusinessException.withFieldErrors(
            ErrorCode.INVALID_REQUEST,
            List.of(
                new FieldErrorItem("status", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 status 값입니다.")));
      }
      parsed.add(value);
    }
    return parsed;
  }

  private String propertyName(Long propertyId) {
    return propertyReferences
        .find(propertyId)
        .map(PropertyReferenceService.PropertySummary::name)
        .filter(name -> name != null)
        .orElse("");
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
