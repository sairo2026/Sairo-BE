package com.sairo.be.domain.legal.service;

import com.sairo.be.global.port.ObjectStorage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 파기 큐 대상별 저장소 대상 산정과 실행이다(OPS-13). 지금은 회원 탈퇴(MEMBER)만 처리기가 있다. 회원 행은 지우지 않고 식별 값을 비우거나 대체해 업무 기록의
 * 참조를 유지한다. 파일은 ObjectStorage 포트로만 지운다.
 */
@Profile("!migrate")
@Service
public class DeletionTargetService {

  public static final String RESULT_SUCCESS = "SUCCESS";
  public static final String RESULT_FAILED = "FAILED";

  public record Target(String targetType, long targetId) {}

  public record Plan(
      boolean supported,
      List<Target> storageTargets,
      List<Target> searchIndexTargets,
      List<String> fileTargets,
      List<String> temporaryExportTargets,
      List<String> holdReasons) {

    public long targetCount() {
      return storageTargets.size() + fileTargets.size() + temporaryExportTargets.size();
    }
  }

  public record Outcome(
      String storageResult,
      String searchIndexResult,
      String fileResult,
      String temporaryExportResult) {}

  private final JdbcClient jdbcClient;
  private final ObjectStorage objectStorage;

  public DeletionTargetService(JdbcClient jdbcClient, ObjectStorage objectStorage) {
    this.jdbcClient = jdbcClient;
    this.objectStorage = objectStorage;
  }

  public Plan plan(String targetType, long targetId, Instant now) {
    if (!DeletionQueueService.MEMBER_TARGET.equals(targetType)) {
      return new Plan(
          false,
          List.of(new Target(targetType, targetId)),
          List.of(),
          List.of(),
          List.of(),
          List.of());
    }
    List<Target> storage = new ArrayList<>();
    boolean pending =
        jdbcClient
                .sql(
                    "SELECT count(*) FROM app_user WHERE id = :id AND (email IS NOT NULL OR phone IS"
                        + " NOT NULL OR kakao_reference_email IS NOT NULL OR kakao_provider_key NOT"
                        + " LIKE 'deleted-%')")
                .param("id", targetId)
                .query(Long.class)
                .single()
            > 0;
    if (pending) {
      storage.add(new Target(DeletionQueueService.MEMBER_TARGET, targetId));
    }
    List<String> exports = memberExportKeys(targetId);
    List<String> holds = new ArrayList<>();
    if (activeGrants("return_grant", "revoked_at", targetId, now) > 0) {
      holds.add("반환권한 유효 기간 중");
    }
    if (activeGrants("archive_grant", "terminated_at", targetId, now) > 0) {
      holds.add("보관권한 유효 기간 중");
    }
    if (!withdrawn(targetId)) {
      holds.add("탈퇴하지 않은 회원");
    }
    return new Plan(true, storage, List.of(), List.of(), exports, holds);
  }

  /** 계획한 대상을 지운다. 회원이 탈퇴 상태가 아니면 운영 저장소는 건드리지 않고 실패로 남긴다. */
  public Outcome execute(String targetType, long targetId, Plan plan) {
    String storage = RESULT_SUCCESS;
    if (!plan.storageTargets().isEmpty()) {
      int updated =
          jdbcClient
              .sql(
                  "UPDATE app_user SET email = NULL, password_hash = NULL, email_verified_at ="
                      + " NULL, kakao_reference_email = NULL, phone = NULL, phone_verified_at ="
                      + " NULL, kakao_provider_key = 'deleted-' || id, name = '탈퇴한 구성원',"
                      + " failed_login_locked_until = NULL WHERE id = :id AND account_status ="
                      + " 'WITHDRAWN'")
              .param("id", targetId)
              .update();
      storage = updated == 1 ? RESULT_SUCCESS : RESULT_FAILED;
      if (updated == 1) {
        jdbcClient
            .sql("DELETE FROM app_user_notification_preference WHERE user_id = :id")
            .param("id", targetId)
            .update();
      }
    }
    String exports = RESULT_SUCCESS;
    for (String key : plan.temporaryExportTargets()) {
      try {
        objectStorage.delete(key);
        jdbcClient
            .sql("UPDATE export_job SET file_object_key = NULL WHERE file_object_key = :key")
            .param("key", key)
            .update();
      } catch (RuntimeException e) {
        exports = RESULT_FAILED;
      }
    }
    return new Outcome(storage, RESULT_SUCCESS, RESULT_SUCCESS, exports);
  }

  private List<String> memberExportKeys(long userId) {
    return jdbcClient
        .sql(
            "SELECT e.file_object_key FROM export_job e JOIN office_membership m ON m.id ="
                + " e.created_by_membership_id WHERE m.user_id = :id AND e.file_object_key IS NOT"
                + " NULL ORDER BY e.id")
        .param("id", userId)
        .query(String.class)
        .list();
  }

  private long activeGrants(String table, String endColumn, long userId, Instant now) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM "
                + table
                + " WHERE granted_to_user_id = :id AND "
                + endColumn
                + " IS NULL AND (expires_at IS NULL OR expires_at > :now)")
        .param("id", userId)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  private boolean withdrawn(long userId) {
    return jdbcClient
            .sql("SELECT count(*) FROM app_user WHERE id = :id AND account_status = 'WITHDRAWN'")
            .param("id", userId)
            .query(Long.class)
            .single()
        > 0;
  }
}
