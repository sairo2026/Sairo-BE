package com.sairo.be.domain.billing.service;

import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Slf4j
@Profile("!migrate")
@Component
public class SystemJobRunService {

  private static final int ERROR_CODE_MAX = 80;

  private final JdbcClient jdbcClient;

  public SystemJobRunService(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public boolean runOnce(
      String jobName, Long officeId, String idempotencyKey, Instant scheduledAt, Runnable work) {
    if (!tryStart(jobName, officeId, idempotencyKey, scheduledAt)) {
      return false;
    }
    try {
      work.run();
      finish(idempotencyKey, "SUCCESS", null);
      return true;
    } catch (RuntimeException e) {
      log.warn("배치 작업이 실패했습니다. job={} key={}", jobName, idempotencyKey, e);
      String code = e.getClass().getSimpleName();
      finish(
          idempotencyKey,
          "FAILED",
          code.length() > ERROR_CODE_MAX ? code.substring(0, ERROR_CODE_MAX) : code);
      return false;
    }
  }

  private boolean tryStart(String jobName, Long officeId, String key, Instant scheduledAt) {
    return !jdbcClient
        .sql(
            """
            INSERT INTO system_job_run (job_name, office_id, idempotency_key, status, scheduled_at)
            VALUES (:jobName, :officeId, :key, 'RUNNING', :scheduledAt)
            ON CONFLICT (idempotency_key) DO UPDATE SET
              status = 'RUNNING', attempt_count = system_job_run.attempt_count + 1,
              started_at = now(), finished_at = NULL, error_code = NULL
            WHERE system_job_run.status = 'FAILED'
            RETURNING id
            """)
        .param("jobName", jobName)
        .param("officeId", officeId)
        .param("key", key)
        .param("scheduledAt", scheduledAt == null ? null : java.sql.Timestamp.from(scheduledAt))
        .query(Long.class)
        .list()
        .isEmpty();
  }

  private void finish(String key, String status, String errorCode) {
    jdbcClient
        .sql(
            "UPDATE system_job_run SET status = :status, finished_at = now(), error_code ="
                + " :errorCode WHERE idempotency_key = :key")
        .param("status", status)
        .param("errorCode", errorCode)
        .param("key", key)
        .update();
  }
}
