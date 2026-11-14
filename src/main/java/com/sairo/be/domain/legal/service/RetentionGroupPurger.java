package com.sairo.be.domain.legal.service;

import com.sairo.be.global.port.ObjectStorage;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 보관 기간 규칙 표의 나머지 데이터군 처리기다(기술설계 3.18). 전체 단위(대상 ID 0) 파기 큐 행 하나가 한 데이터군이다. 기한이 지났더라도 다른 규칙의 행이
 * 참조하거나 처리 중인 행은 지우지 않고 다음 실행이 다시 찾는다. 규칙이 없는 데이터군은 건너뛴다. 파일은 행을 지운 뒤 저장소 포트로 지운다.
 */
@Profile("!migrate")
@Component
public class RetentionGroupPurger {

  public static final String MARKETING_NOTICE_DELIVERY = "MARKETING_NOTICE_DELIVERY";
  public static final String TERMS_AGREEMENT_RETAINED = "TERMS_AGREEMENT_RETAINED";
  public static final String OPS_AUDIT_LOG = "OPS_AUDIT_LOG";
  public static final String GUEST_INTAKE_RECORD = "GUEST_INTAKE_RECORD";
  public static final String PRIVACY_REQUEST_RECORD = "PRIVACY_REQUEST_RECORD";
  public static final String SUPPORT_INQUIRY = "SUPPORT_INQUIRY";
  public static final String REVIEW_RECORD = "REVIEW_RECORD";
  public static final String BILLING_RECORD = "BILLING_RECORD";
  public static final String TAX_DOCUMENT = "TAX_DOCUMENT";
  public static final String AUTHORITY_REQUEST = "AUTHORITY_REQUEST";
  public static final String PROVIDER_CONTRACT_COPY = "PROVIDER_CONTRACT_COPY";

  /** 파기 큐 대상 종류와 보관 기간 규칙 코드다. */
  static final Map<String, String> RULE_CODES =
      Map.ofEntries(
          Map.entry(MARKETING_NOTICE_DELIVERY, "MARKETING_NOTICE_DELIVERY"),
          Map.entry(TERMS_AGREEMENT_RETAINED, "TERMS_AGREEMENT_AFTER_WITHDRAWAL"),
          Map.entry(OPS_AUDIT_LOG, "OPS_AUDIT_LOG"),
          Map.entry(GUEST_INTAKE_RECORD, "GUEST_INTAKE_RECORD"),
          Map.entry(PRIVACY_REQUEST_RECORD, "PRIVACY_REQUEST_RECORD"),
          Map.entry(SUPPORT_INQUIRY, "SUPPORT_INQUIRY"),
          Map.entry(REVIEW_RECORD, "REVIEW_RECORD"),
          Map.entry(BILLING_RECORD, "BILLING_RECORD"),
          Map.entry(TAX_DOCUMENT, "TAX_DOCUMENT"),
          Map.entry(AUTHORITY_REQUEST, "AUTHORITY_REQUEST"),
          Map.entry(PROVIDER_CONTRACT_COPY, "PROVIDER_CONTRACT_COPY"));

  public static final Set<String> TYPES = RULE_CODES.keySet();

  private static final String INTAKE_ELIGIBLE =
      "SELECT i.id FROM legal_guest_privacy_intake i LEFT JOIN privacy_request r ON r.id ="
          + " i.privacy_request_id WHERE (i.status = 'CLOSED' AND i.closed_at < :cutoff) OR"
          + " (i.status = 'CONVERTED' AND r.completed_at IS NOT NULL AND r.completed_at <"
          + " :cutoff)";
  private static final String PRIVACY_REQUEST_ELIGIBLE =
      "SELECT r.id FROM privacy_request r WHERE r.completed_at IS NOT NULL AND r.completed_at <"
          + " :cutoff AND NOT EXISTS (SELECT 1 FROM legal_guest_privacy_intake i WHERE"
          + " i.privacy_request_id = r.id)";
  private static final String INQUIRY_ELIGIBLE =
      "SELECT s.id FROM support_inquiry s WHERE s.status = 'CLOSED' AND s.closed_at IS NOT NULL AND"
          + " s.closed_at < :cutoff AND NOT EXISTS (SELECT 1 FROM privacy_request p WHERE"
          + " p.support_inquiry_id = s.id) AND NOT EXISTS (SELECT 1 FROM ops_approval_request a"
          + " WHERE a.linked_inquiry_id = s.id)";
  private static final String REVIEW_ELIGIBLE =
      "SELECT v.id FROM visit_review v WHERE v.submitted_at < :cutoff AND NOT EXISTS (SELECT 1 FROM"
          + " visit_review_dispute d WHERE d.review_id = v.id AND d.status = 'OPEN')";
  private static final String TERMS_ELIGIBLE =
      "SELECT ta.id FROM terms_agreement ta JOIN app_user u ON u.id = ta.user_id WHERE"
          + " u.withdrawn_at IS NOT NULL AND u.withdrawn_at < :cutoff AND NOT EXISTS (SELECT 1 FROM"
          + " consent_notice_delivery d WHERE d.terms_agreement_id = ta.id) AND NOT EXISTS (SELECT"
          + " 1 FROM subscription_purchase_consent c WHERE c.terms_agreement_id = ta.id)";
  private static final String TAX_ELIGIBLE =
      "SELECT d.id FROM tax_document d WHERE d.status IN ('ISSUED','AMENDED','CANCELLED') AND"
          + " COALESCE(d.issued_on, (d.recorded_at AT TIME ZONE 'Asia/Seoul')::date) <"
          + " :cutoffDate AND (d.retention_until IS NULL OR d.retention_until < :today)";
  private static final String AUTHORITY_ELIGIBLE =
      "SELECT id FROM legal_authority_request WHERE closed_at IS NOT NULL AND closed_at < :cutoff"
          + " AND (retention_until IS NULL OR retention_until < :today)";
  private static final String PROVIDER_COPY_ELIGIBLE =
      "SELECT id FROM legal_provider_contract_copy WHERE valid_until IS NOT NULL AND valid_until <"
          + " :cutoffDate AND (retention_until IS NULL OR retention_until < :today)";
  private static final String SUBSCRIPTION_ELIGIBLE =
      "SELECT s.id FROM subscription s WHERE s.status = 'ENDED' AND s.ended_at IS NOT NULL AND"
          + " s.ended_at < :cutoff AND NOT EXISTS (SELECT 1 FROM billing_invoice i WHERE"
          + " i.subscription_id = s.id AND (i.created_at >= :cutoff OR i.status IN"
          + " ('UNBILLED','PROCESSING','VERIFYING') OR EXISTS (SELECT 1 FROM tax_document d WHERE"
          + " d.billing_invoice_id = i.id) OR EXISTS (SELECT 1 FROM refund_request r WHERE"
          + " r.invoice_id = i.id AND r.status NOT IN ('REJECTED','COMPLETED','FAILED')) OR EXISTS"
          + " (SELECT 1 FROM billing_reconciliation_entry e WHERE e.invoice_id = i.id AND"
          + " e.resolved_at IS NULL AND e.status <> 'MATCHED')))";

  private final JdbcClient jdbcClient;
  private final RetentionRuleLookup ruleLookup;
  private final ObjectStorage objectStorage;

  public RetentionGroupPurger(
      JdbcClient jdbcClient, RetentionRuleLookup ruleLookup, ObjectStorage objectStorage) {
    this.jdbcClient = jdbcClient;
    this.ruleLookup = ruleLookup;
    this.objectStorage = objectStorage;
  }

  public boolean supports(String targetType) {
    return TYPES.contains(targetType);
  }

  /** 지금 지울 수 있는 행 수다. 규칙이 없으면 0이다. */
  public long dueCount(String targetType, Instant now) {
    Optional<Params> params = params(targetType, now);
    if (params.isEmpty()) {
      return 0;
    }
    Params p = params.get();
    return switch (targetType) {
      case MARKETING_NOTICE_DELIVERY ->
          count(
              "SELECT count(*) FROM consent_notice_delivery WHERE created_at < :cutoff AND status <> 'PENDING'",
              p);
      case TERMS_AGREEMENT_RETAINED -> count("SELECT count(*) FROM (" + TERMS_ELIGIBLE + ") x", p);
      case OPS_AUDIT_LOG ->
          count("SELECT count(*) FROM ops_audit_log WHERE created_at < :cutoff", p)
              + count("SELECT count(*) FROM ops_raw_access_grant WHERE executed_at < :cutoff", p)
              + count(
                  "SELECT count(*) FROM ops_approval_request WHERE created_at < :cutoff AND status"
                      + " <> 'PENDING'",
                  p);
      case GUEST_INTAKE_RECORD -> count("SELECT count(*) FROM (" + INTAKE_ELIGIBLE + ") x", p);
      case PRIVACY_REQUEST_RECORD ->
          count("SELECT count(*) FROM (" + PRIVACY_REQUEST_ELIGIBLE + ") x", p);
      case SUPPORT_INQUIRY -> count("SELECT count(*) FROM (" + INQUIRY_ELIGIBLE + ") x", p);
      case REVIEW_RECORD ->
          count("SELECT count(*) FROM (" + REVIEW_ELIGIBLE + ") x", p)
              + count(
                  "SELECT count(*) FROM visit_preferred_broker WHERE released_at IS NOT NULL AND"
                      + " released_at < :cutoff",
                  p)
              + count(
                  "SELECT count(*) FROM visit_selection_trust_snapshot WHERE created_at <"
                      + " :cutoff",
                  p);
      case BILLING_RECORD -> count("SELECT count(*) FROM (" + SUBSCRIPTION_ELIGIBLE + ") x", p);
      case TAX_DOCUMENT -> count("SELECT count(*) FROM (" + TAX_ELIGIBLE + ") x", p);
      case AUTHORITY_REQUEST -> count("SELECT count(*) FROM (" + AUTHORITY_ELIGIBLE + ") x", p);
      case PROVIDER_CONTRACT_COPY ->
          count("SELECT count(*) FROM (" + PROVIDER_COPY_ELIGIBLE + ") x", p);
      default -> 0L;
    };
  }

  /** 기한이 지난 행을 지우고 지운 행 수를 돌려준다. 호출한 쪽 트랜잭션에서 실행한다. */
  public long execute(String targetType, Instant now) {
    Optional<Params> params = params(targetType, now);
    if (params.isEmpty()) {
      return 0;
    }
    Params p = params.get();
    return switch (targetType) {
      case MARKETING_NOTICE_DELIVERY ->
          update(
              "DELETE FROM consent_notice_delivery WHERE created_at < :cutoff AND status <>"
                  + " 'PENDING'",
              p);
      case TERMS_AGREEMENT_RETAINED ->
          update("DELETE FROM terms_agreement WHERE id IN (" + TERMS_ELIGIBLE + ")", p);
      case OPS_AUDIT_LOG -> purgeOpsAudit(p);
      case GUEST_INTAKE_RECORD ->
          update("DELETE FROM legal_guest_privacy_intake WHERE id IN (" + INTAKE_ELIGIBLE + ")", p);
      case PRIVACY_REQUEST_RECORD -> purgePrivacyRequests(p);
      case SUPPORT_INQUIRY -> purgeInquiries(p);
      case REVIEW_RECORD -> purgeReviews(p);
      case BILLING_RECORD -> purgeBilling(p);
      case TAX_DOCUMENT -> purgeTaxDocuments(p);
      case AUTHORITY_REQUEST ->
          update("DELETE FROM legal_authority_request WHERE id IN (" + AUTHORITY_ELIGIBLE + ")", p);
      case PROVIDER_CONTRACT_COPY -> purgeProviderCopies(p);
      default -> throw new IllegalArgumentException("처리기가 없는 파기 대상 종류다: " + targetType);
    };
  }

  private long purgeOpsAudit(Params p) {
    List<String> keys =
        query(
            "DELETE FROM ops_raw_access_grant WHERE executed_at < :cutoff RETURNING"
                + " file_object_key",
            p);
    long deleted = keys.size();
    deleted +=
        update(
            "DELETE FROM ops_approval_request WHERE created_at < :cutoff AND status <> 'PENDING'"
                + " AND NOT EXISTS (SELECT 1 FROM ops_raw_access_grant g WHERE"
                + " g.approval_request_id = ops_approval_request.id)",
            p);
    deleted += update("DELETE FROM ops_audit_log WHERE created_at < :cutoff", p);
    deleteFiles(keys);
    return deleted;
  }

  private long purgePrivacyRequests(Params p) {
    update(
        "DELETE FROM privacy_request_event WHERE privacy_request_id IN ("
            + PRIVACY_REQUEST_ELIGIBLE
            + ")",
        p);
    return update("DELETE FROM privacy_request WHERE id IN (" + PRIVACY_REQUEST_ELIGIBLE + ")", p);
  }

  private long purgeInquiries(Params p) {
    List<String> keys =
        query(
            "SELECT object_key FROM support_inquiry_attachment WHERE inquiry_id IN ("
                + INQUIRY_ELIGIBLE
                + ")",
            p);
    long deleted = update("DELETE FROM support_inquiry WHERE id IN (" + INQUIRY_ELIGIBLE + ")", p);
    deleteFiles(keys);
    return deleted;
  }

  private long purgeReviews(Params p) {
    update("DELETE FROM visit_review_dispute WHERE review_id IN (" + REVIEW_ELIGIBLE + ")", p);
    long deleted = update("DELETE FROM visit_review WHERE id IN (" + REVIEW_ELIGIBLE + ")", p);
    deleted +=
        update(
            "DELETE FROM visit_preferred_broker WHERE released_at IS NOT NULL AND released_at <"
                + " :cutoff",
            p);
    deleted += update("DELETE FROM visit_selection_trust_snapshot WHERE created_at < :cutoff", p);
    return deleted;
  }

  private long purgeTaxDocuments(Params p) {
    long deleted =
        update(
            "DELETE FROM tax_document WHERE id IN ("
                + TAX_ELIGIBLE
                + ") AND original_document_id IS NOT NULL",
            p);
    deleted +=
        update(
            "DELETE FROM tax_document WHERE id IN ("
                + TAX_ELIGIBLE
                + ") AND NOT EXISTS (SELECT 1 FROM tax_document a WHERE a.original_document_id ="
                + " tax_document.id)",
            p);
    return deleted;
  }

  private long purgeProviderCopies(Params p) {
    List<String> keys =
        query(
            "DELETE FROM legal_provider_contract_copy WHERE id IN ("
                + PROVIDER_COPY_ELIGIBLE
                + ") RETURNING storage_key",
            p);
    deleteFiles(keys);
    return keys.size();
  }

  /**
   * 끝난 지 보관 기간이 지난 구독마다 청구 시도, 대사 항목, 환불 신청, 기업도입 견적의 청구서 연결, 청구서, 가격 변경 안내, 구독 해지, 구독 구매 동의, 구독
   * 순서로 지운다. 세금 증빙이 남은 청구서나 처리 중인 환불이 있는 구독은 대상에서 뺀다.
   */
  private long purgeBilling(Params p) {
    List<Long> subscriptions =
        jdbcClient
            .sql(SUBSCRIPTION_ELIGIBLE + " ORDER BY s.id")
            .param("cutoff", p.cutoff())
            .query(Long.class)
            .list();
    if (subscriptions.isEmpty()) {
      return 0;
    }
    List<Long> invoices =
        jdbcClient
            .sql("SELECT id FROM billing_invoice WHERE subscription_id IN (:ids)")
            .param("ids", subscriptions)
            .query(Long.class)
            .list();
    if (!invoices.isEmpty()) {
      delete(
          "DELETE FROM billing_reconciliation_entry WHERE invoice_id IN (:ids) OR"
              + " refund_request_id IN (SELECT id FROM refund_request WHERE invoice_id IN (:ids))",
          invoices);
      delete("DELETE FROM billing_charge_attempt WHERE invoice_id IN (:ids)", invoices);
      delete("DELETE FROM refund_request WHERE invoice_id IN (:ids)", invoices);
      delete(
          "UPDATE enterprise_quote SET billing_invoice_id = NULL WHERE billing_invoice_id IN (:ids)",
          invoices);
      delete("DELETE FROM billing_invoice WHERE id IN (:ids)", invoices);
    }
    delete("DELETE FROM pricing_change_notice WHERE subscription_id IN (:ids)", subscriptions);
    delete("DELETE FROM subscription_cancellation WHERE subscription_id IN (:ids)", subscriptions);
    delete(
        "DELETE FROM subscription_purchase_consent WHERE subscription_id IN (:ids)", subscriptions);
    delete("DELETE FROM subscription WHERE id IN (:ids)", subscriptions);
    return subscriptions.size();
  }

  private void delete(String sql, List<Long> ids) {
    jdbcClient.sql(sql).param("ids", ids).update();
  }

  private void deleteFiles(List<String> keys) {
    for (String key : keys) {
      if (key != null) {
        objectStorage.delete(key);
      }
    }
  }

  private List<String> query(String sql, Params p) {
    return named(sql, p).query(String.class).list();
  }

  private long count(String sql, Params p) {
    return named(sql, p).query(Long.class).single();
  }

  private long update(String sql, Params p) {
    return named(sql, p).update();
  }

  private JdbcClient.StatementSpec named(String sql, Params p) {
    return jdbcClient
        .sql(sql)
        .param("cutoff", p.cutoff())
        .param("cutoffDate", p.cutoffDate())
        .param("today", p.today());
  }

  private Optional<Params> params(String targetType, Instant now) {
    String rule = RULE_CODES.get(targetType);
    if (rule == null) {
      return Optional.empty();
    }
    OptionalInt days = ruleLookup.retentionDays(rule);
    if (days.isEmpty()) {
      return Optional.empty();
    }
    Instant cutoff = now.minus(Duration.ofDays(days.getAsInt()));
    return Optional.of(
        new Params(
            Timestamp.from(cutoff),
            Date.valueOf(com.sairo.be.global.support.TimeRules.displayDate(cutoff)),
            RetentionDeletionTargets.today(now)));
  }

  private record Params(Timestamp cutoff, Date cutoffDate, Date today) {}
}
