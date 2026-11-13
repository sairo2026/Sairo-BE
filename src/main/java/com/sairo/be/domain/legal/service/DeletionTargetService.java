package com.sairo.be.domain.legal.service;

import com.sairo.be.global.port.ObjectStorage;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 파기 큐 대상별 저장소 대상 산정과 실행이다(OPS-13). 회원 탈퇴(MEMBER)와 보관 기한 만료 데이터군({@link RetentionDeletionTargets})의
 * 처리기가 있다. 회원 행은 지우지 않고 식별 값을 비우거나 대체해 업무 기록의 참조를 유지하며 소속 행의 자격 번호도 함께 비운다. 파일은 ObjectStorage 포트로만
 * 지운다.
 */
@Profile("!migrate")
@Service
public class DeletionTargetService {

  private static final Logger log = LoggerFactory.getLogger(DeletionTargetService.class);

  public static final String RESULT_SUCCESS = "SUCCESS";
  public static final String RESULT_FAILED = "FAILED";

  public record Target(String targetType, long targetId) {}

  public record Plan(
      boolean supported,
      List<Target> storageTargets,
      List<Target> searchIndexTargets,
      List<String> fileTargets,
      List<String> temporaryExportTargets,
      List<String> holdReasons,
      long storageRowCount) {

    public Plan(
        boolean supported,
        List<Target> storageTargets,
        List<Target> searchIndexTargets,
        List<String> fileTargets,
        List<String> temporaryExportTargets,
        List<String> holdReasons) {
      this(
          supported,
          storageTargets,
          searchIndexTargets,
          fileTargets,
          temporaryExportTargets,
          holdReasons,
          storageTargets.size());
    }

    /** 운영 저장소 대상은 묶음 한 건으로 보이더라도 건수는 실제 행 수로 센다. */
    public long targetCount() {
      return storageRowCount + fileTargets.size() + temporaryExportTargets.size();
    }
  }

  public record Outcome(
      String storageResult,
      String searchIndexResult,
      String fileResult,
      String temporaryExportResult) {}

  private final JdbcClient jdbcClient;
  private final ObjectStorage objectStorage;
  private final RetentionDeletionTargets retentionTargets;
  private final OfficeDataPurger officeDataPurger;
  private final PlatformTransactionManager transactionManager;
  private final Clock clock;

  public DeletionTargetService(
      JdbcClient jdbcClient,
      ObjectStorage objectStorage,
      RetentionDeletionTargets retentionTargets,
      OfficeDataPurger officeDataPurger,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.jdbcClient = jdbcClient;
    this.objectStorage = objectStorage;
    this.retentionTargets = retentionTargets;
    this.officeDataPurger = officeDataPurger;
    this.transactionManager = transactionManager;
    this.clock = clock;
  }

  public Plan plan(String targetType, long targetId, Instant now) {
    if (officeDataPurger.supports(targetType)) {
      long due = officeDataPurger.dueCount(targetId);
      return new Plan(
          true,
          due == 0 ? List.of() : List.of(new Target(targetType, targetId)),
          List.of(),
          List.of(),
          List.of(),
          officeDataPurger.holdReasons(targetId, now),
          due);
    }
    if (retentionTargets.supports(targetType)) {
      long due = retentionTargets.dueCount(targetType, targetId, now);
      return new Plan(
          true,
          due == 0 ? List.of() : List.of(new Target(targetType, targetId)),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          due);
    }
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
                    "SELECT count(*) FROM app_user u WHERE u.id = :id AND (u.email IS NOT NULL OR"
                        + " u.phone IS NOT NULL OR u.kakao_reference_email IS NOT NULL OR"
                        + " u.kakao_provider_key NOT LIKE 'deleted-%' OR EXISTS (SELECT 1 FROM"
                        + " office_membership m WHERE m.user_id = u.id AND m.qualification_number"
                        + " IS NOT NULL))")
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
    if (officeDataPurger.supports(targetType)) {
      return isolated(targetType, () -> officeDataPurger.execute(targetId, clock.instant()));
    }
    if (RetentionGroupPurger.TYPES.contains(targetType)) {
      return isolated(
          targetType, () -> retentionTargets.execute(targetType, targetId, clock.instant()));
    }
    if (retentionTargets.supports(targetType)) {
      retentionTargets.execute(targetType, targetId, clock.instant());
      return new Outcome(RESULT_SUCCESS, RESULT_SUCCESS, RESULT_SUCCESS, RESULT_SUCCESS);
    }
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
        jdbcClient
            .sql(
                "UPDATE office_membership SET qualification_number = NULL WHERE user_id = :id AND"
                    + " qualification_number IS NOT NULL")
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

  /** 별도 트랜잭션에서 실행하고 실패하면 아무것도 지우지 않고 저장소 결과를 실패로 돌려준다. */
  private Outcome isolated(String targetType, LongSupplier work) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    try {
      template.execute(status -> work.getAsLong());
      return new Outcome(RESULT_SUCCESS, RESULT_SUCCESS, RESULT_SUCCESS, RESULT_SUCCESS);
    } catch (RuntimeException e) {
      log.warn("파기 실행 실패 targetType={} error={}", targetType, e.getClass().getSimpleName());
      return new Outcome(RESULT_FAILED, RESULT_SUCCESS, RESULT_SUCCESS, RESULT_SUCCESS);
    }
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
