package com.sairo.be.domain.legal.service;

import com.sairo.be.domain.legal.service.RetentionDeletionTargets.OfficeDue;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalInt;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보관·파기 법률 배치의 본체다(기술설계 3.18). 보관 기간은 보관 기간 규칙 표를 읽고 규칙이 없으면 그 데이터군은 건너뛴다. 모든 단계는 멱등이라 같은 날 다시 돌거나
 * 운영자가 재실행해도 결과가 같다. 이름·연락처·식별자 원문은 기록하지 않고 건수만 돌려준다.
 */
@Profile("!migrate")
@Service
public class LegalRetentionBatchService {

  public static final int LINK_LOG_CHUNK = 10_000;

  public record SweepResult(int queued, int refreshed, long dueRows, int contactsWiped) {}

  public record AnonymizeResult(int linksAnonymized, int authEventsCleared) {}

  private final JdbcClient jdbcClient;
  private final RetentionDeletionTargets targets;
  private final DeletionQueueService queueService;
  private final RetentionRuleLookup ruleLookup;
  private final Clock clock;

  public LegalRetentionBatchService(
      JdbcClient jdbcClient,
      RetentionDeletionTargets targets,
      DeletionQueueService queueService,
      RetentionRuleLookup ruleLookup,
      Clock clock) {
    this.jdbcClient = jdbcClient;
    this.targets = targets;
    this.queueService = queueService;
    this.ruleLookup = ruleLookup;
    this.clock = clock;
  }

  /**
   * retention-sweep. 비회원 링크 입력과 승인자 개인정보는 사무소마다, 로그인 기록과 카카오 연결 끊기 이력은 전체로 파기 큐에 올린다. 비회원 권리 요청
   * 연락처는 종결 뒤 규칙 일수가 지나면 큐를 거치지 않고 바로 비운다(삭제 방식이 암호문 비우기라서다).
   */
  @Transactional
  public SweepResult sweep() {
    Instant now = clock.instant();
    int queued = 0;
    int refreshed = 0;
    long dueRows = 0;
    for (String type : RetentionDeletionTargets.OFFICE_TYPES.stream().sorted().toList()) {
      for (OfficeDue due : targets.dueOffices(type, now)) {
        dueRows += due.count();
        if (queueService.enqueueRetention(type, due.officeId(), due.officeId(), due.count())) {
          queued++;
        } else {
          refreshed++;
        }
      }
    }
    for (String type : RetentionDeletionTargets.GLOBAL_TYPES.stream().sorted().toList()) {
      long count = targets.dueCount(type, RetentionDeletionTargets.GLOBAL_TARGET_ID, now);
      if (count == 0) {
        continue;
      }
      dueRows += count;
      if (queueService.enqueueRetention(
          type, null, RetentionDeletionTargets.GLOBAL_TARGET_ID, count)) {
        queued++;
      } else {
        refreshed++;
      }
    }
    return new SweepResult(queued, refreshed, dueRows, wipeGuestIntakeContacts(now));
  }

  /**
   * approver-anonymize. 보관 기한이 지난 승인자 매물 링크는 행을 지우지 않고 토큰 해시와 마지막 접속 시각을 비우며 폐기·익명화 시각을 남기고 그 링크의
   * 안내 확인 기록을 지운다. 같은 실행에서 규칙 일수가 지난 승인자 인증 사건의 접속 IP 변환값을 비운다.
   */
  @Transactional
  public AnonymizeResult anonymizeApproverLinks() {
    Instant now = clock.instant();
    Timestamp at = Timestamp.from(now);
    var ids =
        jdbcClient
            .sql(
                "SELECT id FROM visit_approver_property_link WHERE anonymized_at IS NULL AND"
                    + " retention_until < :today ORDER BY id FOR UPDATE")
            .param("today", RetentionDeletionTargets.today(now))
            .query(Long.class)
            .list();
    int links = 0;
    if (!ids.isEmpty()) {
      jdbcClient
          .sql("DELETE FROM consent_guest_notice_ack WHERE approver_property_link_id IN (:ids)")
          .param("ids", ids)
          .update();
      links =
          jdbcClient
              .sql(
                  "UPDATE visit_approver_property_link SET token_hash = NULL, last_accessed_at ="
                      + " NULL, revoked_at = COALESCE(revoked_at, :at), anonymized_at = :at WHERE"
                      + " id IN (:ids)")
              .param("at", at)
              .param("ids", ids)
              .update();
    }
    int events = 0;
    OptionalInt days = ruleLookup.retentionDays("GUEST_INPUT_AFTER_APPROVAL_END");
    if (days.isPresent()) {
      events =
          jdbcClient
              .sql(
                  "UPDATE approver_auth_event SET ip_hmac = NULL, hmac_key_version = NULL WHERE"
                      + " ip_hmac IS NOT NULL AND occurred_at < :cutoff")
              .param("cutoff", Timestamp.from(now.minus(Duration.ofDays(days.getAsInt()))))
              .update();
    }
    return new AnonymizeResult(links, events);
  }

  /** legal-link-log-purge. 삭제 기준일이 지난 비회원 링크 접속 로그를 1만 건씩 지운다. 지운 건수를 돌려준다. */
  public long purgeLinkAccessLogs() {
    var today = RetentionDeletionTargets.today(clock.instant());
    long total = 0;
    while (true) {
      int deleted =
          jdbcClient
              .sql(
                  "DELETE FROM legal_public_link_access_log WHERE id IN (SELECT id FROM"
                      + " legal_public_link_access_log WHERE purge_after < :today ORDER BY id"
                      + " LIMIT :limit)")
              .param("today", today)
              .param("limit", LINK_LOG_CHUNK)
              .update();
      total += deleted;
      if (deleted < LINK_LOG_CHUNK) {
        return total;
      }
    }
  }

  private int wipeGuestIntakeContacts(Instant now) {
    OptionalInt days = ruleLookup.retentionDays("GUEST_INTAKE_CONTACT");
    if (days.isEmpty()) {
      return 0;
    }
    Timestamp at = Timestamp.from(now);
    return jdbcClient
        .sql(
            "UPDATE legal_guest_privacy_intake SET contact_encrypted = NULL, contact_wiped_at ="
                + " :at WHERE contact_encrypted IS NOT NULL AND closed_at IS NOT NULL AND closed_at"
                + " < :cutoff")
        .param("at", at)
        .param("cutoff", Timestamp.from(now.minus(Duration.ofDays(days.getAsInt()))))
        .update();
  }
}
