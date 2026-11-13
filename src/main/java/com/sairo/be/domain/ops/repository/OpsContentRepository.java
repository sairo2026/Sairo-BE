package com.sairo.be.domain.ops.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 공지·정책 문서·회사 제공 정보의 운영 조회·쓰기다(OPS-08). */
@Profile("!migrate")
@Repository
public class OpsContentRepository {

  public record AnnouncementRow(
      long id,
      String title,
      String content,
      int versionNo,
      Long previousVersionId,
      String audienceType,
      String audienceStatus,
      Long audienceOfficeId,
      Instant startsAt,
      Instant endsAt,
      String importance,
      String detailUrl,
      boolean marketing,
      String status,
      Instant scheduledAt,
      Instant publishedAt,
      Instant endedAt,
      Instant createdAt,
      long createdByOperatorId,
      Long reviewedByOperatorId) {}

  public record NewAnnouncement(
      String title,
      String content,
      int versionNo,
      Long previousVersionId,
      String audienceType,
      String audienceStatus,
      Long audienceOfficeId,
      Instant startsAt,
      Instant endsAt,
      String importance,
      String detailUrl,
      boolean marketing,
      long operatorId,
      Instant now) {}

  public record PolicyFilter(String docType, String status) {}

  public record PolicyRow(
      long id,
      String docType,
      String version,
      String status,
      String body,
      String changeSummary,
      Instant noticeAt,
      boolean requiresConsent,
      Instant effectiveAt,
      Instant publishedAt,
      long createdByOperatorId,
      Long reviewedByOperatorId,
      Long publishedByOperatorId) {}

  public record NewPolicy(
      String docType,
      String version,
      String body,
      String changeSummary,
      Instant noticeAt,
      boolean requiresConsent,
      Instant effectiveAt,
      long operatorId) {}

  public record InfoRow(
      long id,
      int versionNo,
      String businessName,
      String representativeName,
      String businessNumber,
      String address,
      String contactPhone,
      String ecommerceNotice,
      String supportEmail,
      String supportHours,
      String emergencyContact,
      String privacyOfficer,
      String privacyContact,
      String privacyRequestPath,
      String cancellationPath,
      Instant inquiryDeliveryTestedAt,
      Long reviewedByOperatorId,
      Instant publishedAt) {}

  private static final String ANNOUNCEMENT_COLUMNS =
      "SELECT id, title, content, version_no, previous_version_id, audience_type, audience_status,"
          + " audience_office_id, starts_at, ends_at, importance, detail_url, is_marketing, status,"
          + " scheduled_at, published_at, ended_at, created_at, created_by_operator_id,"
          + " reviewed_by_operator_id FROM platform_announcement";

  private static final String POLICY_COLUMNS =
      "SELECT id, doc_type, version, status, body, change_summary, notice_at, requires_consent,"
          + " effective_at, published_at, created_by_operator_id, reviewed_by_operator_id,"
          + " published_by_operator_id FROM policy_document";

  private static final String INFO_COLUMNS =
      "SELECT id, version_no, business_name, representative_name, business_number, address,"
          + " contact_phone, ecommerce_notice, support_email, support_hours, emergency_contact,"
          + " privacy_officer, privacy_contact, privacy_request_path, cancellation_path,"
          + " inquiry_delivery_tested_at, reviewed_by_operator_id, published_at FROM"
          + " company_service_info";

  /** 마케팅 수신에 동의한 승인 대표가 한 명도 없는 사무소다. 수신 설정이 없으면 미동의로 본다. */
  private static final String NO_MARKETING_OWNER =
      " AND NOT EXISTS (SELECT 1 FROM office_membership m JOIN app_user_notification_preference p"
          + " ON p.user_id = m.user_id AND p.channel_key = 'MARKETING' AND p.enabled WHERE"
          + " m.office_id = o.id AND m.role = 'OWNER' AND m.status = 'APPROVED')";

  private final JdbcClient jdbcClient;

  public OpsContentRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  // 공지

  public List<AnnouncementRow> announcements(String status, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = statusWhere(status, params);
    params.put("limit", limit);
    params.put("offset", offset);
    return jdbcClient
        .sql(
            ANNOUNCEMENT_COLUMNS
                + where
                + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
        .params(params)
        .query(OpsContentRepository::mapAnnouncement)
        .list();
  }

  public long countAnnouncements(String status) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = statusWhere(status, params);
    return jdbcClient
        .sql("SELECT count(*) FROM platform_announcement" + where)
        .params(params)
        .query(Long.class)
        .single();
  }

  public Optional<AnnouncementRow> announcement(long id) {
    return jdbcClient
        .sql(ANNOUNCEMENT_COLUMNS + " WHERE id = :id")
        .param("id", id)
        .query(OpsContentRepository::mapAnnouncement)
        .optional();
  }

  public Optional<AnnouncementRow> lockAnnouncement(long id) {
    return jdbcClient
        .sql(ANNOUNCEMENT_COLUMNS + " WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(OpsContentRepository::mapAnnouncement)
        .optional();
  }

  public long insertAnnouncement(NewAnnouncement value) {
    return jdbcClient
        .sql(
            "INSERT INTO platform_announcement (title, content, version_no, previous_version_id,"
                + " audience_type, audience_status, audience_office_id, starts_at, ends_at,"
                + " importance, detail_url, is_marketing, status, created_by_operator_id,"
                + " created_at) VALUES (:title, :content, :versionNo, :previous, :audienceType,"
                + " :audienceStatus, :audienceOffice, :starts, :ends, :importance, :detailUrl,"
                + " :marketing, 'DRAFT', :operator, :now) RETURNING id")
        .param("title", value.title())
        .param("content", value.content())
        .param("versionNo", value.versionNo())
        .param("previous", value.previousVersionId())
        .param("audienceType", value.audienceType())
        .param("audienceStatus", value.audienceStatus())
        .param("audienceOffice", value.audienceOfficeId())
        .param("starts", Timestamp.from(value.startsAt()))
        .param("ends", Timestamp.from(value.endsAt()))
        .param("importance", value.importance())
        .param("detailUrl", value.detailUrl())
        .param("marketing", value.marketing())
        .param("operator", value.operatorId())
        .param("now", Timestamp.from(value.now()))
        .query(Long.class)
        .single();
  }

  public void submitAnnouncementReview(long id, long operatorId) {
    jdbcClient
        .sql(
            "UPDATE platform_announcement SET status = 'REVIEW', reviewed_by_operator_id ="
                + " :operator WHERE id = :id")
        .param("operator", operatorId)
        .param("id", id)
        .update();
  }

  public void publishAnnouncement(long id, Instant publishedAt) {
    jdbcClient
        .sql(
            "UPDATE platform_announcement SET status = 'PUBLISHED', published_at = :at WHERE id ="
                + " :id")
        .param("at", Timestamp.from(publishedAt))
        .param("id", id)
        .update();
  }

  public void scheduleAnnouncement(long id, Instant scheduledAt) {
    jdbcClient
        .sql(
            "UPDATE platform_announcement SET status = 'SCHEDULED', scheduled_at = :at WHERE id ="
                + " :id")
        .param("at", Timestamp.from(scheduledAt))
        .param("id", id)
        .update();
  }

  public void endAnnouncement(long id, Instant endedAt) {
    jdbcClient
        .sql("UPDATE platform_announcement SET status = 'ENDED', ended_at = :at WHERE id = :id")
        .param("at", Timestamp.from(endedAt))
        .param("id", id)
        .update();
  }

  /** 예약 시각이 지난 예약 공지를 게시한다. 게시 시각은 예약 시각이다. */
  public record DueAnnouncement(long id, Instant scheduledAt) {}

  public List<DueAnnouncement> dueAnnouncements(Instant now) {
    return jdbcClient
        .sql(
            "SELECT id, scheduled_at FROM platform_announcement WHERE status = 'SCHEDULED' AND"
                + " scheduled_at <= :now ORDER BY scheduled_at, id")
        .param("now", Timestamp.from(now))
        .query((rs, i) -> new DueAnnouncement(rs.getLong("id"), rs.getTimestamp(2).toInstant()))
        .list();
  }

  /** 아직 예약 상태이고 예약 시각이 지난 공지 하나를 예약 시각으로 게시한다. */
  public int publishDueAnnouncement(long id, Instant now) {
    return jdbcClient
        .sql(
            "UPDATE platform_announcement SET status = 'PUBLISHED', published_at = scheduled_at"
                + " WHERE id = :id AND status = 'SCHEDULED' AND scheduled_at <= :now")
        .param("id", id)
        .param("now", Timestamp.from(now))
        .update();
  }

  public boolean officeExists(long officeId) {
    return jdbcClient
            .sql("SELECT count(*) FROM office WHERE id = :id")
            .param("id", officeId)
            .query(Long.class)
            .single()
        > 0;
  }

  /** 공지 노출 대상 사무소 수다. 전체는 모든 사무소, 구독 상태별은 그 상태의 구독이 있는 사무소, 특정 사무소는 그 사무소다. */
  public long countTargets(AnnouncementRow row, boolean marketingExcludedOnly) {
    StringBuilder sql = new StringBuilder("SELECT count(*) FROM office o WHERE 1 = 1");
    Map<String, Object> params = new LinkedHashMap<>();
    switch (row.audienceType()) {
      case "SUBSCRIPTION_STATUS" -> {
        sql.append(
            " AND EXISTS (SELECT 1 FROM subscription s WHERE s.office_id = o.id AND s.status ="
                + " :status)");
        params.put("status", row.audienceStatus());
      }
      case "OFFICE" -> {
        sql.append(" AND o.id = :office");
        params.put("office", row.audienceOfficeId());
      }
      default -> {}
    }
    if (marketingExcludedOnly) {
      sql.append(NO_MARKETING_OWNER);
    }
    return jdbcClient.sql(sql.toString()).params(params).query(Long.class).single();
  }

  // 정책 문서

  public List<PolicyRow> policies(PolicyFilter filter, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = policyWhere(filter, params);
    params.put("limit", limit);
    params.put("offset", offset);
    return jdbcClient
        .sql(
            POLICY_COLUMNS
                + where
                + " ORDER BY doc_type, effective_at DESC NULLS LAST, id DESC LIMIT :limit OFFSET"
                + " :offset")
        .params(params)
        .query(OpsContentRepository::mapPolicy)
        .list();
  }

  public long countPolicies(PolicyFilter filter) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = policyWhere(filter, params);
    return jdbcClient
        .sql("SELECT count(*) FROM policy_document" + where)
        .params(params)
        .query(Long.class)
        .single();
  }

  public Optional<PolicyRow> lockPolicy(long id) {
    return jdbcClient
        .sql(POLICY_COLUMNS + " WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(OpsContentRepository::mapPolicy)
        .optional();
  }

  public boolean policyVersionExists(String docType, String version) {
    return jdbcClient
            .sql(
                "SELECT count(*) FROM policy_document WHERE doc_type = :type AND version = :version")
            .param("type", docType)
            .param("version", version)
            .query(Long.class)
            .single()
        > 0;
  }

  public long insertPolicy(NewPolicy value) {
    return jdbcClient
        .sql(
            "INSERT INTO policy_document (doc_type, version, status, body, change_summary,"
                + " notice_at, requires_consent, effective_at, created_by_operator_id) VALUES"
                + " (:type, :version, 'DRAFT', :body, :summary, :notice, :consent, :effective,"
                + " :operator) RETURNING id")
        .param("type", value.docType())
        .param("version", value.version())
        .param("body", value.body())
        .param("summary", value.changeSummary())
        .param("notice", Timestamp.from(value.noticeAt()))
        .param("consent", value.requiresConsent())
        .param("effective", Timestamp.from(value.effectiveAt()))
        .param("operator", value.operatorId())
        .query(Long.class)
        .single();
  }

  public void submitPolicyReview(long id, long operatorId) {
    jdbcClient
        .sql(
            "UPDATE policy_document SET status = 'REVIEW', reviewed_by_operator_id = :operator"
                + " WHERE id = :id")
        .param("operator", operatorId)
        .param("id", id)
        .update();
  }

  public void publishPolicy(long id, long operatorId, Instant now) {
    jdbcClient
        .sql(
            "UPDATE policy_document SET status = 'PUBLISHED', published_by_operator_id ="
                + " :operator, published_at = :now WHERE id = :id")
        .param("operator", operatorId)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  // 회사 제공 정보

  public List<InfoRow> infos(int limit, long offset) {
    return jdbcClient
        .sql(INFO_COLUMNS + " ORDER BY version_no DESC LIMIT :limit OFFSET :offset")
        .param("limit", limit)
        .param("offset", offset)
        .query(OpsContentRepository::mapInfo)
        .list();
  }

  public long countInfos() {
    return jdbcClient.sql("SELECT count(*) FROM company_service_info").query(Long.class).single();
  }

  public Optional<InfoRow> latestInfo() {
    return jdbcClient
        .sql(INFO_COLUMNS + " ORDER BY version_no DESC LIMIT 1")
        .query(OpsContentRepository::mapInfo)
        .optional();
  }

  public List<String> publishedPolicyTypes() {
    return jdbcClient
        .sql("SELECT DISTINCT doc_type FROM policy_document WHERE status = 'PUBLISHED'")
        .query(String.class)
        .list();
  }

  public boolean approvedStandardPriceExists() {
    return jdbcClient
            .sql(
                "SELECT count(*) FROM price_schedule WHERE audience = 'STANDARD' AND status IN"
                    + " ('SCHEDULED','ACTIVE') AND approved_by_operator_id IS NOT NULL")
            .query(Long.class)
            .single()
        > 0;
  }

  public Optional<InfoRow> latestPublishedInfo() {
    return jdbcClient
        .sql(INFO_COLUMNS + " WHERE published_at IS NOT NULL ORDER BY version_no DESC LIMIT 1")
        .query(OpsContentRepository::mapInfo)
        .optional();
  }

  public Optional<InfoRow> lockInfo(long id) {
    return jdbcClient
        .sql(INFO_COLUMNS + " WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(OpsContentRepository::mapInfo)
        .optional();
  }

  public int nextInfoVersion() {
    return jdbcClient
        .sql("SELECT COALESCE(max(version_no), 0) + 1 FROM company_service_info")
        .query(Integer.class)
        .single();
  }

  public long insertInfo(int versionNo, InfoRow value) {
    return jdbcClient
        .sql(
            "INSERT INTO company_service_info (version_no, business_name, representative_name,"
                + " business_number, address, contact_phone, ecommerce_notice, support_email,"
                + " support_hours, emergency_contact, privacy_officer, privacy_contact,"
                + " privacy_request_path, cancellation_path) VALUES (:versionNo, :businessName,"
                + " :representative, :businessNumber, :address, :phone, :ecommerce, :email,"
                + " :hours, :emergency, :officer, :privacyContact, :privacyPath, :cancellation)"
                + " RETURNING id")
        .param("versionNo", versionNo)
        .param("businessName", value.businessName())
        .param("representative", value.representativeName())
        .param("businessNumber", value.businessNumber())
        .param("address", value.address())
        .param("phone", value.contactPhone())
        .param("ecommerce", value.ecommerceNotice())
        .param("email", value.supportEmail())
        .param("hours", value.supportHours())
        .param("emergency", value.emergencyContact())
        .param("officer", value.privacyOfficer())
        .param("privacyContact", value.privacyContact())
        .param("privacyPath", value.privacyRequestPath())
        .param("cancellation", value.cancellationPath())
        .query(Long.class)
        .single();
  }

  /** 수신 시험 성공을 기록하고 아직 게시 전이면 이 시각에 게시한다. */
  public void recordInquiryTest(long id, long operatorId, Instant now) {
    jdbcClient
        .sql(
            "UPDATE company_service_info SET inquiry_delivery_tested_at = :now,"
                + " reviewed_by_operator_id = :operator, published_at = COALESCE(published_at,"
                + " :now) WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("operator", operatorId)
        .param("id", id)
        .update();
  }

  private static String statusWhere(String status, Map<String, Object> params) {
    if (status == null) {
      return "";
    }
    params.put("status", status);
    return " WHERE status = :status";
  }

  private static String policyWhere(PolicyFilter filter, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (filter.docType() != null) {
      where.append(" AND doc_type = :type");
      params.put("type", filter.docType());
    }
    if (filter.status() != null) {
      where.append(" AND status = :status");
      params.put("status", filter.status());
    }
    return where.toString();
  }

  private static AnnouncementRow mapAnnouncement(ResultSet rs, int n) throws SQLException {
    return new AnnouncementRow(
        rs.getLong("id"),
        rs.getString("title"),
        rs.getString("content"),
        rs.getInt("version_no"),
        rs.getObject("previous_version_id", Long.class),
        rs.getString("audience_type"),
        rs.getString("audience_status"),
        rs.getObject("audience_office_id", Long.class),
        instant(rs, "starts_at"),
        instant(rs, "ends_at"),
        rs.getString("importance"),
        rs.getString("detail_url"),
        rs.getBoolean("is_marketing"),
        rs.getString("status"),
        instant(rs, "scheduled_at"),
        instant(rs, "published_at"),
        instant(rs, "ended_at"),
        instant(rs, "created_at"),
        rs.getLong("created_by_operator_id"),
        rs.getObject("reviewed_by_operator_id", Long.class));
  }

  private static PolicyRow mapPolicy(ResultSet rs, int n) throws SQLException {
    return new PolicyRow(
        rs.getLong("id"),
        rs.getString("doc_type"),
        rs.getString("version"),
        rs.getString("status"),
        rs.getString("body"),
        rs.getString("change_summary"),
        instant(rs, "notice_at"),
        rs.getBoolean("requires_consent"),
        instant(rs, "effective_at"),
        instant(rs, "published_at"),
        rs.getLong("created_by_operator_id"),
        rs.getObject("reviewed_by_operator_id", Long.class),
        rs.getObject("published_by_operator_id", Long.class));
  }

  private static InfoRow mapInfo(ResultSet rs, int n) throws SQLException {
    return new InfoRow(
        rs.getLong("id"),
        rs.getInt("version_no"),
        rs.getString("business_name"),
        rs.getString("representative_name"),
        rs.getString("business_number"),
        rs.getString("address"),
        rs.getString("contact_phone"),
        rs.getString("ecommerce_notice"),
        rs.getString("support_email"),
        rs.getString("support_hours"),
        rs.getString("emergency_contact"),
        rs.getString("privacy_officer"),
        rs.getString("privacy_contact"),
        rs.getString("privacy_request_path"),
        rs.getString("cancellation_path"),
        instant(rs, "inquiry_delivery_tested_at"),
        rs.getObject("reviewed_by_operator_id", Long.class),
        instant(rs, "published_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
