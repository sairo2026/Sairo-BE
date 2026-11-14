package com.sairo.be.domain.billing.service;

import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * system_job_run 키로 배치 작업을 한 번만 실행한다. 같은 키의 실행은 실패(FAILED)일 때만 다시 시작하고 실행 중(RUNNING)이거나
 * 성공(SUCCESS)이면 건너뛴다. 키가 UNIQUE라서 여러 인스턴스가 같은 작업을 동시에 시작해도 하나만 시작된다.
 */
@Slf4j
@Profile("!migrate")
@Component
public class SystemJobRunService {

  /** 운영자 재실행 결과다. */
  public enum RerunOutcome {
    NOT_STARTED,
    SUCCESS,
    FAILED
  }

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
    } catch (SystemJobSkipped e) {
      log.info("배치 작업을 건너뛰었습니다. job={} key={} code={}", jobName, idempotencyKey, e.code());
      finish(idempotencyKey, "SUCCESS", e.code());
      return true;
    } catch (RuntimeException e) {
      log.warn("배치 작업이 실패했습니다. job={} key={}", jobName, idempotencyKey, e);
      finish(idempotencyKey, "FAILED", errorCode(e));
      return false;
    }
  }

  /**
   * 실패한 실행을 원래 멱등키로 다시 실행한다. 실패 상태일 때만 시도 회수를 올리고 실행 중으로 바꾸며, 그 사이 다른 실행이 시작했거나 성공했으면 실행하지 않는다. 작업은
   * 현재 상태를 스스로 다시 검사한다.
   */
  public RerunOutcome rerunFailed(String idempotencyKey, Runnable work) {
    boolean started =
        !jdbcClient
            .sql(
                """
                UPDATE system_job_run SET status = 'RUNNING', attempt_count = attempt_count + 1,
                  started_at = now(), finished_at = NULL, error_code = NULL
                WHERE idempotency_key = :key AND status = 'FAILED'
                RETURNING id
                """)
            .param("key", idempotencyKey)
            .query(Long.class)
            .list()
            .isEmpty();
    if (!started) {
      return RerunOutcome.NOT_STARTED;
    }
    try {
      work.run();
      finish(idempotencyKey, "SUCCESS", null);
      return RerunOutcome.SUCCESS;
    } catch (SystemJobSkipped e) {
      log.info("재실행을 건너뛰었습니다. key={} code={}", idempotencyKey, e.code());
      finish(idempotencyKey, "SUCCESS", e.code());
      return RerunOutcome.SUCCESS;
    } catch (RuntimeException e) {
      log.warn("재실행이 실패했습니다. key={}", idempotencyKey, e);
      finish(idempotencyKey, "FAILED", errorCode(e));
      return RerunOutcome.FAILED;
    }
  }

  private static String errorCode(RuntimeException e) {
    String code =
        e instanceof SystemJobFailure failure ? failure.code() : e.getClass().getSimpleName();
    return code.length() > ERROR_CODE_MAX ? code.substring(0, ERROR_CODE_MAX) : code;
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
