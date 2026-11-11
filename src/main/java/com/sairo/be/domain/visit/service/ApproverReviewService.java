package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.visit.dto.response.ApproverVisitRequestCompareResponse.PrivacyNotice;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ApproverReviewItem;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ApproverVisitReviewListResponse;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ApproverVisitReviewResponse;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ReviewAnswers;
import com.sairo.be.domain.visit.repository.MembershipNameRepository;
import com.sairo.be.domain.visit.repository.PreferredBrokerRepository;
import com.sairo.be.domain.visit.repository.VisitReviewRepository;
import com.sairo.be.domain.visit.repository.VisitReviewRepository.Answers;
import com.sairo.be.domain.visit.repository.VisitReviewRepository.Review;
import com.sairo.be.domain.visit.repository.VisitReviewRepository.Target;
import com.sairo.be.domain.visit.repository.VisitReviewRepository.TargetWithReview;
import com.sairo.be.domain.visit.service.ApproverReviewContext.Opened;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.support.IfMatchVersion;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class ApproverReviewService {

  public static final int REVIEW_WINDOW_DAYS = 14;
  public static final int MAX_REVISIONS = 2;
  static final Duration REVIEW_WINDOW = Duration.ofDays(REVIEW_WINDOW_DAYS);
  static final Duration LIST_WINDOW = Duration.ofDays(90);
  private static final Set<String> KEYS =
      Set.of("punctuality", "advanceContact", "asRequested", "acknowledgedNoticeVersion");
  private static final Set<String> ANSWERS = Set.of("YES", "NO", "SKIP");

  public record Upserted(boolean created, ApproverVisitReviewResponse body) {}

  private final ApproverReviewContext context;
  private final VisitReviewRepository reviewRepository;
  private final PreferredBrokerRepository preferredRepository;
  private final MembershipNameRepository nameRepository;
  private final ApproverLinkNoticeProvider noticeProvider;
  private final Clock clock;

  public ApproverReviewService(
      ApproverReviewContext context,
      VisitReviewRepository reviewRepository,
      PreferredBrokerRepository preferredRepository,
      MembershipNameRepository nameRepository,
      ApproverLinkNoticeProvider noticeProvider,
      Clock clock) {
    this.context = context;
    this.reviewRepository = reviewRepository;
    this.preferredRepository = preferredRepository;
    this.nameRepository = nameRepository;
    this.noticeProvider = noticeProvider;
    this.clock = clock;
  }

  @Transactional
  public ApproverVisitReviewListResponse list(String token, String clientIp) {
    return list(context.open(token, clientIp));
  }

  /** 승인자 세션으로 평가 대상 목록을 본다. 공동 승인자는 읽기 전용이다. */
  @Transactional(readOnly = true)
  public ApproverVisitReviewListResponse listForProfile(Long profileId, Long approvalId) {
    return list(context.openForProfile(profileId, approvalId));
  }

  private ApproverVisitReviewListResponse list(Opened opened) {
    Instant now = now();
    List<TargetWithReview> rows =
        opened
            .dataApprovalId()
            .map(id -> reviewRepository.listForApprover(id, now, now.minus(LIST_WINDOW)))
            .orElse(List.of());
    Set<Long> preferred = new HashSet<>();
    opened
        .dataApprovalId()
        .ifPresent(
            id ->
                preferredRepository.active(id).forEach(p -> preferred.add(p.brokerMembershipId())));
    Map<Long, String> brokers =
        nameRepository.displayNames(
            rows.stream().map(row -> row.target().applicantMembershipId()).distinct().toList());
    Map<Long, String> offices = new HashMap<>();
    List<ApproverReviewItem> items =
        rows.stream()
            .map(
                row -> {
                  Target target = row.target();
                  Review review = row.review();
                  Instant deadline = deadlineOf(target, review);
                  String status = visibleStatus(review);
                  int revisions = review == null ? 0 : review.revisionCount();
                  boolean editable =
                      opened.writable()
                          && now.isBefore(deadline)
                          && !"LOCKED".equals(status)
                          && (review == null || revisions < MAX_REVISIONS);
                  return new ApproverReviewItem(
                      target.itemId(),
                      TimeRules.toDisplayOffset(target.startAt()),
                      TimeRules.toDisplayOffset(target.endAt()),
                      TimeRules.toDisplayOffset(target.actualVisitedAt()),
                      offices.computeIfAbsent(target.applicantOfficeId(), context::officeName),
                      brokers.getOrDefault(target.applicantMembershipId(), ""),
                      TimeRules.toDisplayOffset(deadline),
                      status,
                      editable,
                      revisions,
                      MAX_REVISIONS - revisions,
                      review == null ? null : Long.valueOf(review.version()),
                      review == null || "WITHDRAWN".equals(review.status())
                          ? null
                          : answers(review),
                      preferred.contains(target.applicantMembershipId()));
                })
            .toList();
    return new ApproverVisitReviewListResponse(
        context.propertyAddress(opened.approval()), REVIEW_WINDOW_DAYS, items, notice());
  }

  @Transactional
  public Upserted upsert(
      String token,
      Long scheduleItemId,
      String ifMatch,
      String idempotencyKey,
      JsonNode body,
      String clientIp) {
    return upsert(
        context.openForWrite(token, clientIp), scheduleItemId, ifMatch, idempotencyKey, body);
  }

  /** 승인자 세션으로 평가를 입력하거나 수정한다. 대표 승인자 출입승인만 가능하다. */
  @Transactional
  public Upserted upsertForProfile(
      Long profileId,
      Long approvalId,
      Long scheduleItemId,
      String ifMatch,
      String idempotencyKey,
      JsonNode body) {
    return upsert(
        context.openForProfileWrite(profileId, approvalId),
        scheduleItemId,
        ifMatch,
        idempotencyKey,
        body);
  }

  private Upserted upsert(
      Opened opened, Long scheduleItemId, String ifMatch, String idempotencyKey, JsonNode body) {
    Answers answers = parse(body);
    requireNotice(body);
    Long approvalId = opened.approval().getId();
    Target target =
        reviewRepository
            .lockTarget(scheduleItemId, approvalId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!"COMPLETED".equals(target.result())) {
      throw new BusinessException(ErrorCode.VISIT_REVIEW_WINDOW_CLOSED);
    }
    Instant now = now();
    Review existing = reviewRepository.lockByItem(scheduleItemId).orElse(null);
    if (existing == null) {
      Instant deadline = target.resultRecordedAt().plus(REVIEW_WINDOW);
      if (!now.isBefore(deadline)) {
        throw new BusinessException(ErrorCode.VISIT_REVIEW_WINDOW_CLOSED);
      }
      reviewRepository
          .insert(target, answers, opened.linkId(), opened.profileId(), deadline, now)
          .orElseThrow(() -> BusinessException.versionConflict(1));
      return new Upserted(
          true, response(reviewRepository.findByItem(scheduleItemId).orElseThrow()));
    }
    boolean retry = idempotencyKey != null && !idempotencyKey.isBlank();
    if (retry && isReplay(existing, answers, ifMatch)) {
      return new Upserted(existing.revisionCount() == 0, response(existing));
    }
    if (!now.isBefore(existing.deadlineAt())) {
      throw new BusinessException(ErrorCode.VISIT_REVIEW_WINDOW_CLOSED);
    }
    if (isLocked(existing) || existing.revisionCount() >= MAX_REVISIONS) {
      throw new BusinessException(ErrorCode.VISIT_REVIEW_LOCKED);
    }
    long expected = IfMatchVersion.parse(ifMatch);
    if (expected != existing.version()) {
      throw BusinessException.versionConflict(existing.version());
    }
    reviewRepository.revise(existing.id(), answers, opened.linkId(), opened.profileId(), now);
    return new Upserted(false, response(reviewRepository.findByItem(scheduleItemId).orElseThrow()));
  }

  @Transactional
  public ApproverVisitReviewResponse withdraw(
      String token, Long scheduleItemId, String ifMatch, String clientIp) {
    return withdraw(context.openForWrite(token, clientIp), scheduleItemId, ifMatch);
  }

  @Transactional
  public ApproverVisitReviewResponse withdrawForProfile(
      Long profileId, Long approvalId, Long scheduleItemId, String ifMatch) {
    return withdraw(context.openForProfileWrite(profileId, approvalId), scheduleItemId, ifMatch);
  }

  private ApproverVisitReviewResponse withdraw(Opened opened, Long scheduleItemId, String ifMatch) {
    long expected = IfMatchVersion.parse(ifMatch);
    reviewRepository
        .lockTarget(scheduleItemId, opened.approval().getId())
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Review existing =
        reviewRepository
            .lockByItem(scheduleItemId)
            .filter(review -> !"WITHDRAWN".equals(review.status()))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Instant now = now();
    if (!now.isBefore(existing.deadlineAt())) {
      throw new BusinessException(ErrorCode.VISIT_REVIEW_WINDOW_CLOSED);
    }
    if (isLocked(existing)) {
      throw new BusinessException(ErrorCode.VISIT_REVIEW_LOCKED);
    }
    if (expected != existing.version()) {
      throw BusinessException.versionConflict(existing.version());
    }
    reviewRepository.withdraw(existing.id(), opened.linkId(), opened.profileId(), now);
    return response(reviewRepository.findByItem(scheduleItemId).orElseThrow());
  }

  static boolean isLocked(Review review) {
    return review.disputed()
        || "DISPUTED".equals(review.status())
        || "REMOVED".equals(review.status());
  }

  static String visibleStatus(Review review) {
    if (review == null) {
      return "NONE";
    }
    if (isLocked(review)) {
      return "LOCKED";
    }
    return "WITHDRAWN".equals(review.status()) ? "WITHDRAWN" : "SUBMITTED";
  }

  static ReviewAnswers answers(Review review) {
    return new ReviewAnswers(review.punctuality(), review.advanceContact(), review.asRequested());
  }

  private static Instant deadlineOf(Target target, Review review) {
    return review != null ? review.deadlineAt() : target.resultRecordedAt().plus(REVIEW_WINDOW);
  }

  private static boolean isReplay(Review existing, Answers answers, String ifMatch) {
    if (!"ACTIVE".equals(existing.status())
        || !existing.punctuality().equals(answers.punctuality())
        || !existing.advanceContact().equals(answers.advanceContact())
        || !existing.asRequested().equals(answers.asRequested())) {
      return false;
    }
    if (ifMatch == null || ifMatch.isBlank()) {
      return existing.revisionCount() == 0 && existing.version() == 1;
    }
    try {
      return IfMatchVersion.parse(ifMatch) == existing.version() - 1;
    } catch (BusinessException e) {
      return false;
    }
  }

  private ApproverVisitReviewResponse response(Review review) {
    boolean withdrawn = "WITHDRAWN".equals(review.status());
    return new ApproverVisitReviewResponse(
        review.itemId(),
        withdrawn ? "WITHDRAWN" : "SUBMITTED",
        withdrawn ? null : answers(review),
        review.revisionCount(),
        MAX_REVISIONS - review.revisionCount(),
        TimeRules.toDisplayOffset(review.deadlineAt()),
        TimeRules.toDisplayOffset(review.submittedAt()),
        TimeRules.toDisplayOffset(review.lastRevisedAt()),
        review.version());
  }

  private static Answers parse(JsonNode body) {
    CustomerInput.requireObject(body, KEYS);
    String punctuality = answer(body, "punctuality");
    String contact = answer(body, "advanceContact");
    String asRequested = answer(body, "asRequested");
    if ("SKIP".equals(punctuality) && "SKIP".equals(contact) && "SKIP".equals(asRequested)) {
      String message = "세 항목 가운데 하나 이상은 예 또는 아니오로 답해야 합니다.";
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(
              new FieldErrorItem("punctuality", FieldErrorCode.INVALID_VALUE, message),
              new FieldErrorItem("advanceContact", FieldErrorCode.INVALID_VALUE, message),
              new FieldErrorItem("asRequested", FieldErrorCode.INVALID_VALUE, message)));
    }
    CustomerInput.requiredText(body, "acknowledgedNoticeVersion", 50, false);
    return new Answers(punctuality, contact, asRequested);
  }

  private static String answer(JsonNode body, String key) {
    if (CustomerInput.isNull(body, key)) {
      throw CustomerInput.invalid(key, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    JsonNode node = body.get(key);
    if (!node.isString() || !ANSWERS.contains(node.asString())) {
      throw CustomerInput.invalid(key, FieldErrorCode.NOT_ALLOWED, "YES, NO, SKIP 중 하나여야 합니다.");
    }
    return node.asString();
  }

  void requireNotice(JsonNode body) {
    String version = body.get("acknowledgedNoticeVersion").asString();
    if (!noticeProvider.current().noticeVersion().equals(version)) {
      throw new BusinessException(ErrorCode.NOTICE_VERSION_CHANGED);
    }
  }

  PrivacyNotice notice() {
    ApproverLinkNoticeProvider.Notice notice = noticeProvider.current();
    return new PrivacyNotice(notice.noticeCode(), notice.noticeVersion(), notice.retentionDays());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
