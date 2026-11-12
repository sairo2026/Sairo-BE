package com.sairo.be.domain.ops.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 시스템 작업 실행 이력·장애·개인정보 사고·연동 설정의 운영 조회·쓰기다(OPS-09). */
@Profile("!migrate")
@Repository
public class OpsSystemRepository {

  private final JdbcClient jdbcClient;

  public OpsSystemRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  // 시스템 작업

  public record JobFilter(String jobName, String status, Long officeId) {}

  public record JobRow(
      long id,
      String jobName,
      Long officeId,
      String officeName,
      String idempotencyKey,
      String status,
      Instant scheduledAt,
      Instant startedAt,
      Instant finishedAt,
      int attemptCount,
      String errorCode,
      Instant nextRunAt,
      Long assignedOperatorId,
      String assignedOperatorName) {}

  private static final String JOB_SELECT =
      "SELECT j.id, j.job_name, j.office_id, o.name AS office_name, j.idempotency_key, j.status,"
          + " j.scheduled_at, j.started_at, j.finished_at, j.attempt_count, j.error_code,"
          + " j.next_run_at, j.assigned_operator_id, a.name AS operator_name FROM system_job_run j"
          + " LEFT JOIN office o ON o.id = j.office_id LEFT JOIN operator_account a ON a.id ="
          + " j.assigned_operator_id";

  public List<JobRow> jobs(JobFilter filter, int limit, long offset) {
    List<Object[]> params = new ArrayList<>();
    String where = jobWhere(filter, params);
    var spec =
        jdbcClient.sql(
            JOB_SELECT
                + where
                + " ORDER BY j.started_at DESC, j.id DESC LIMIT :limit OFFSET :offset");
    for (Object[] param : params) {
      spec = spec.param((String) param[0], param[1]);
    }
    return spec.param("limit", limit).param("offset", offset).query(this::mapJob).list();
  }

  public long countJobs(JobFilter filter) {
    List<Object[]> params = new ArrayList<>();
    String where = jobWhere(filter, params);
    var spec = jdbcClient.sql("SELECT count(*) FROM system_job_run j" + where);
    for (Object[] param : params) {
      spec = spec.param((String) param[0], param[1]);
    }
    return spec.query(Long.class).single();
  }

  private static String jobWhere(JobFilter filter, List<Object[]> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (filter.jobName() != null) {
      where.append(" AND j.job_name = :jobName");
      params.add(new Object[] {"jobName", filter.jobName()});
    }
    if (filter.status() != null) {
      where.append(" AND j.status = :status");
      params.add(new Object[] {"status", filter.status()});
    }
    if (filter.officeId() != null) {
      where.append(" AND j.office_id = :officeId");
      params.add(new Object[] {"officeId", filter.officeId()});
    }
    return where.toString();
  }

  public Optional<JobRow> job(long id) {
    return jdbcClient
        .sql(JOB_SELECT + " WHERE j.id = :id")
        .param("id", id)
        .query(this::mapJob)
        .optional();
  }

  private JobRow mapJob(ResultSet rs, int rowNum) throws SQLException {
    return new JobRow(
        rs.getLong("id"),
        rs.getString("job_name"),
        rs.getObject("office_id", Long.class),
        rs.getString("office_name"),
        rs.getString("idempotency_key"),
        rs.getString("status"),
        instant(rs, "scheduled_at"),
        instant(rs, "started_at"),
        instant(rs, "finished_at"),
        rs.getInt("attempt_count"),
        rs.getString("error_code"),
        instant(rs, "next_run_at"),
        rs.getObject("assigned_operator_id", Long.class),
        rs.getString("operator_name"));
  }

  // 장애

  public record IncidentRow(
      long id,
      String title,
      String affectedServices,
      String affectedOffices,
      String status,
      Instant detectedAt,
      Instant startedAt,
      Instant recoveredAt,
      Instant closedAt,
      Long assignedOperatorId,
      String rootCause,
      String responseActions,
      String customerNotice,
      String preventionTasks,
      String summary) {}

  public record NewIncident(
      String title,
      String affectedServices,
      String affectedOffices,
      Instant detectedAt,
      Instant startedAt,
      String rootCause,
      Long assignedOperatorId) {}

  public record IncidentChange(
      String status,
      Instant recoveredAt,
      Instant closedAt,
      Long assignedOperatorId,
      String rootCause,
      String responseActions,
      String customerNotice,
      String preventionTasks,
      String summary) {}

  private static final String INCIDENT_SELECT =
      "SELECT id, title, affected_services, affected_offices, status, detected_at, started_at,"
          + " recovered_at, closed_at, assigned_operator_id, root_cause, response_actions,"
          + " customer_notice, prevention_tasks, summary FROM incident_log";

  public long insertIncident(NewIncident incident) {
    return jdbcClient
        .sql(
            "INSERT INTO incident_log (title, affected_services, affected_offices, status,"
                + " detected_at, started_at, assigned_operator_id, root_cause) VALUES (:title,"
                + " :services, :offices, 'DETECTED', :detectedAt, :startedAt, :operator,"
                + " :rootCause) RETURNING id")
        .param("title", incident.title())
        .param("services", incident.affectedServices())
        .param("offices", incident.affectedOffices())
        .param("detectedAt", Timestamp.from(incident.detectedAt()))
        .param("startedAt", Timestamp.from(incident.startedAt()))
        .param("operator", incident.assignedOperatorId())
        .param("rootCause", incident.rootCause())
        .query(Long.class)
        .single();
  }

  public List<IncidentRow> incidents(String status, int limit, long offset) {
    return jdbcClient
        .sql(
            INCIDENT_SELECT
                + " WHERE (CAST(:status AS VARCHAR) IS NULL OR status = :status)"
                + " ORDER BY detected_at DESC, id DESC LIMIT :limit OFFSET :offset")
        .param("status", status)
        .param("limit", limit)
        .param("offset", offset)
        .query(this::incident)
        .list();
  }

  public long countIncidents(String status) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM incident_log WHERE (CAST(:status AS VARCHAR) IS NULL OR status ="
                + " :status)")
        .param("status", status)
        .query(Long.class)
        .single();
  }

  public Optional<IncidentRow> lockedIncident(long id) {
    return jdbcClient
        .sql(INCIDENT_SELECT + " WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(this::incident)
        .optional();
  }

  public boolean incidentExists(long id) {
    return jdbcClient
            .sql("SELECT count(*) FROM incident_log WHERE id = :id")
            .param("id", id)
            .query(Long.class)
            .single()
        > 0;
  }

  public void updateIncident(long id, IncidentChange change) {
    jdbcClient
        .sql(
            "UPDATE incident_log SET status = :status, recovered_at = :recoveredAt, closed_at ="
                + " :closedAt, assigned_operator_id = :operator, root_cause = :rootCause,"
                + " response_actions = :actions, customer_notice = :notice, prevention_tasks ="
                + " :prevention, summary = :summary WHERE id = :id")
        .param("status", change.status())
        .param("recoveredAt", timestamp(change.recoveredAt()))
        .param("closedAt", timestamp(change.closedAt()))
        .param("operator", change.assignedOperatorId())
        .param("rootCause", change.rootCause())
        .param("actions", change.responseActions())
        .param("notice", change.customerNotice())
        .param("prevention", change.preventionTasks())
        .param("summary", change.summary())
        .param("id", id)
        .update();
  }

  private IncidentRow incident(ResultSet rs, int rowNum) throws SQLException {
    return new IncidentRow(
        rs.getLong("id"),
        rs.getString("title"),
        rs.getString("affected_services"),
        rs.getString("affected_offices"),
        rs.getString("status"),
        instant(rs, "detected_at"),
        instant(rs, "started_at"),
        instant(rs, "recovered_at"),
        instant(rs, "closed_at"),
        rs.getObject("assigned_operator_id", Long.class),
        rs.getString("root_cause"),
        rs.getString("response_actions"),
        rs.getString("customer_notice"),
        rs.getString("prevention_tasks"),
        rs.getString("summary"));
  }

  public long countExistingOffices(List<Long> officeIds) {
    if (officeIds.isEmpty()) {
      return 0;
    }
    return jdbcClient
        .sql("SELECT count(*) FROM office WHERE id IN (:ids)")
        .param("ids", officeIds)
        .query(Long.class)
        .single();
  }

  public boolean activeOperator(long operatorId) {
    return jdbcClient
            .sql("SELECT count(*) FROM operator_account WHERE id = :id AND status = 'ACTIVE'")
            .param("id", operatorId)
            .query(Long.class)
            .single()
        > 0;
  }

  // 개인정보 사고

  public record PrivacyIncidentRow(
      long id,
      long incidentLogId,
      String affectedScope,
      String reportRequirement,
      String noticeRequirement,
      Instant reportDueAt,
      Instant noticeDueAt,
      String responseEvidence,
      long assignedOperatorId,
      Instant createdAt) {}

  public record NewPrivacyIncident(
      long incidentLogId,
      String affectedScope,
      String reportRequirement,
      String noticeRequirement,
      Instant reportDueAt,
      Instant noticeDueAt,
      long operatorId,
      Instant now) {}

  private static final String PRIVACY_SELECT =
      "SELECT id, incident_log_id, affected_scope, report_requirement, notice_requirement,"
          + " report_due_at, notice_due_at, response_evidence, assigned_operator_id, created_at"
          + " FROM privacy_incident";

  /** 사건당 1건이다. 이미 연결된 사건이면 비어 있다. */
  public Optional<Long> insertPrivacyIncident(NewPrivacyIncident incident) {
    return jdbcClient
        .sql(
            "INSERT INTO privacy_incident (incident_log_id, affected_scope, report_requirement,"
                + " notice_requirement, report_due_at, notice_due_at, assigned_operator_id,"
                + " created_at) VALUES (:incident, :scope, :report, :notice, :reportDue,"
                + " :noticeDue, :operator, :now) ON CONFLICT (incident_log_id) DO NOTHING"
                + " RETURNING id")
        .param("incident", incident.incidentLogId())
        .param("scope", incident.affectedScope())
        .param("report", incident.reportRequirement())
        .param("notice", incident.noticeRequirement())
        .param("reportDue", timestamp(incident.reportDueAt()))
        .param("noticeDue", timestamp(incident.noticeDueAt()))
        .param("operator", incident.operatorId())
        .param("now", Timestamp.from(incident.now()))
        .query(Long.class)
        .optional();
  }

  public List<PrivacyIncidentRow> privacyIncidents(int limit, long offset) {
    return jdbcClient
        .sql(PRIVACY_SELECT + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
        .param("limit", limit)
        .param("offset", offset)
        .query(this::privacyIncident)
        .list();
  }

  public long countPrivacyIncidents() {
    return jdbcClient.sql("SELECT count(*) FROM privacy_incident").query(Long.class).single();
  }

  public int updatePrivacyEvidence(long id, String evidence) {
    return jdbcClient
        .sql("UPDATE privacy_incident SET response_evidence = :evidence WHERE id = :id")
        .param("evidence", evidence)
        .param("id", id)
        .update();
  }

  private PrivacyIncidentRow privacyIncident(ResultSet rs, int rowNum) throws SQLException {
    return new PrivacyIncidentRow(
        rs.getLong("id"),
        rs.getLong("incident_log_id"),
        rs.getString("affected_scope"),
        rs.getString("report_requirement"),
        rs.getString("notice_requirement"),
        instant(rs, "report_due_at"),
        instant(rs, "notice_due_at"),
        rs.getString("response_evidence"),
        rs.getLong("assigned_operator_id"),
        instant(rs, "created_at"));
  }

  // 연동 설정

  public record IntegrationRow(
      long id,
      String environment,
      String provider,
      String functionCode,
      boolean enabled,
      String secretReferenceId,
      String maskedConnection,
      String processingRegion,
      String retentionPolicy,
      String contractStatus,
      String consentStatus,
      Instant contractNoticeReviewedAt,
      String lastCheckResult,
      Instant lastSuccessAt,
      Long assignedOperatorId,
      Long approvedByOperatorId) {}

  public record IntegrationChange(
      boolean enabled,
      String provider,
      String processingRegion,
      String retentionPolicy,
      String contractStatus,
      String consentStatus,
      Instant contractNoticeReviewedAt,
      Long approvedByOperatorId) {}

  private static final String INTEGRATION_SELECT =
      "SELECT id, environment, provider, function_code, enabled, secret_reference_id,"
          + " masked_connection, processing_region, retention_policy, contract_status,"
          + " consent_status, contract_notice_reviewed_at, last_check_result, last_success_at,"
          + " assigned_operator_id, approved_by_operator_id FROM integration_config";

  public List<IntegrationRow> integrations(
      String environment, String functionCode, int limit, long offset) {
    return jdbcClient
        .sql(
            INTEGRATION_SELECT
                + " WHERE (CAST(:environment AS VARCHAR) IS NULL OR environment = :environment)"
                + " AND (CAST(:functionCode AS VARCHAR) IS NULL OR function_code = :functionCode)"
                + " ORDER BY environment, function_code, provider, id LIMIT :limit OFFSET :offset")
        .param("environment", environment)
        .param("functionCode", functionCode)
        .param("limit", limit)
        .param("offset", offset)
        .query(this::mapIntegration)
        .list();
  }

  public long countIntegrations(String environment, String functionCode) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM integration_config WHERE (CAST(:environment AS VARCHAR) IS NULL"
                + " OR environment = :environment) AND (CAST(:functionCode AS VARCHAR) IS NULL OR"
                + " function_code = :functionCode)")
        .param("environment", environment)
        .param("functionCode", functionCode)
        .query(Long.class)
        .single();
  }

  public Optional<IntegrationRow> integration(long id) {
    return jdbcClient
        .sql(INTEGRATION_SELECT + " WHERE id = :id")
        .param("id", id)
        .query(this::mapIntegration)
        .optional();
  }

  public Optional<IntegrationRow> lockedIntegration(long id) {
    return jdbcClient
        .sql(INTEGRATION_SELECT + " WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(this::mapIntegration)
        .optional();
  }

  public boolean otherIntegrationExists(
      long id, String environment, String provider, String functionCode) {
    return jdbcClient
            .sql(
                "SELECT count(*) FROM integration_config WHERE id <> :id AND environment = :env"
                    + " AND provider = :provider AND function_code = :function")
            .param("id", id)
            .param("env", environment)
            .param("provider", provider)
            .param("function", functionCode)
            .query(Long.class)
            .single()
        > 0;
  }

  public void updateIntegration(long id, IntegrationChange change) {
    jdbcClient
        .sql(
            "UPDATE integration_config SET enabled = :enabled, provider = :provider,"
                + " processing_region = :region, retention_policy = :retention, contract_status ="
                + " :contract, consent_status = :consent, contract_notice_reviewed_at ="
                + " :reviewedAt, approved_by_operator_id = :approvedBy WHERE id = :id")
        .param("enabled", change.enabled())
        .param("provider", change.provider())
        .param("region", change.processingRegion())
        .param("retention", change.retentionPolicy())
        .param("contract", change.contractStatus())
        .param("consent", change.consentStatus())
        .param("reviewedAt", timestamp(change.contractNoticeReviewedAt()))
        .param("approvedBy", change.approvedByOperatorId())
        .param("id", id)
        .update();
  }

  public void recordCheck(long id, String result, Instant now) {
    jdbcClient
        .sql(
            "UPDATE integration_config SET last_check_result = :result, last_success_at = CASE"
                + " WHEN :success THEN :now ELSE last_success_at END WHERE id = :id")
        .param("result", result)
        .param("success", "SUCCESS".equals(result))
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  private IntegrationRow mapIntegration(ResultSet rs, int rowNum) throws SQLException {
    return new IntegrationRow(
        rs.getLong("id"),
        rs.getString("environment"),
        rs.getString("provider"),
        rs.getString("function_code"),
        rs.getBoolean("enabled"),
        rs.getString("secret_reference_id"),
        rs.getString("masked_connection"),
        rs.getString("processing_region"),
        rs.getString("retention_policy"),
        rs.getString("contract_status"),
        rs.getString("consent_status"),
        instant(rs, "contract_notice_reviewed_at"),
        rs.getString("last_check_result"),
        instant(rs, "last_success_at"),
        rs.getObject("assigned_operator_id", Long.class),
        rs.getObject("approved_by_operator_id", Long.class));
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
