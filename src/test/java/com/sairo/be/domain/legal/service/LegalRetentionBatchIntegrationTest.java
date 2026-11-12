package com.sairo.be.domain.legal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.billing.service.SystemJobRunService;
import com.sairo.be.domain.billing.service.SystemJobRunService.RerunOutcome;
import com.sairo.be.domain.legal.service.DeletionTargetService.Outcome;
import com.sairo.be.domain.legal.service.DeletionTargetService.Plan;
import com.sairo.be.domain.ops.service.LegalRetentionJobService;
import com.sairo.be.global.support.TextCipher;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitOutcomeTestBase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

/** 보관·파기 법률 배치. 시험 데이터만 만들고 지운다. */
@IntegrationTest
class LegalRetentionBatchIntegrationTest extends VisitOutcomeTestBase {

  @Autowired private LegalRetentionBatchService batchService;
  @Autowired private LegalRetentionJobService jobService;
  @Autowired private DeletionTargetService targetService;
  @Autowired private SystemJobRunService jobRunService;
  @Autowired private TextCipher textCipher;

  private SelectionFixture visit;
  private long officeId;

  @BeforeEach
  void seed() throws Exception {
    jdbcTemplate.execute(
        new ClassPathResource("db/migration/V15__seed_terms_catalog_and_legal_retention_rules.sql")
            .getContentAsString(StandardCharsets.UTF_8));
    visit = confirmed();
    officeId = visit.base().owner().office().getId();
  }

  private long linkOf(long coordinationId) {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM visit_customer_link WHERE coordination_id = ? ORDER BY id LIMIT 1",
        Long.class,
        coordinationId);
  }

  private void expireApproval(long approvalId) {
    jdbcTemplate.update(
        "UPDATE property_access_approval SET revoked_at = now() - interval '100 days',"
            + " revocation_reason = '철회', revocation_recorded_at = now() - interval '100 days',"
            + " revoked_by_user_id = ?, retention_until = current_date - 1 WHERE id = ?",
        visit.base().owner().user().getId(),
        approvalId);
  }

  private long insertLogin(String ago) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO login_history (user_id, auth_method, created_at) VALUES (?, 'EMAIL', now() -"
            + " ?::interval) RETURNING id",
        Long.class,
        visit.base().owner().user().getId(),
        ago);
  }

  private long insertFinishedUnlink(String ago) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO legal_kakao_unlink_event (direction, user_id, status, completed_at,"
            + " occurred_at) VALUES ('OUTBOUND_CALL', ?, 'SUCCEEDED', now() - ?::interval, now() -"
            + " ?::interval) RETURNING id",
        Long.class,
        visit.base().owner().user().getId(),
        ago,
        ago);
  }

  private long insertClosedIntake(String closedAgo) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO legal_guest_privacy_intake (office_id, link_kind, link_id, request_type,"
            + " requester_role, contact_channel, contact_encrypted, request_note, received_at,"
            + " internal_due_at, status, closed_at, closed_reason) VALUES (?, 'CUSTOMER', 1,"
            + " 'ACCESS', 'SELF', 'PHONE', ?, '열람 요청', now() - interval '60 days', now() -"
            + " interval '50 days', 'CLOSED', now() - ?::interval, 'DUPLICATE') RETURNING id",
        Long.class,
        officeId,
        textCipher.encrypt("010-1111-2222"),
        closedAgo);
  }

  private List<Map<String, Object>> queue() {
    return jdbcTemplate.queryForList(
        "SELECT target_type, target_id, office_id, target_count, reason FROM deletion_queue"
            + " ORDER BY target_type");
  }

  @Test
  void 보관_기한_정리는_기한이_지난_데이터군만_파기_큐에_올리고_같은_날_다시_돌지_않는다() {
    long link = linkOf(visit.coordinationId());
    jdbcTemplate.update(
        "UPDATE visit_customer_link SET retention_until = current_date - 1 WHERE id = ?", link);
    expireApproval(visit.base().approvalId());
    insertLogin("400 days");
    insertLogin("10 days");
    insertFinishedUnlink("400 days");
    insertFinishedUnlink("10 days");
    long oldIntake = insertClosedIntake("40 days");
    long recentIntake = insertClosedIntake("5 days");

    assertThat(jobService.runScheduled(LegalRetentionJobService.SWEEP_JOB, Instant.now())).isTrue();

    List<Map<String, Object>> rows = queue();
    assertThat(rows)
        .extracting(row -> row.get("target_type"))
        .containsExactly(
            "APPROVER_PERSONAL", "GUEST_LINK_INPUT", "KAKAO_UNLINK_EVENT", "LOGIN_HISTORY");
    assertThat(rows.get(0).get("office_id")).isEqualTo(officeId);
    assertThat(rows.get(0).get("target_id")).isEqualTo(officeId);
    assertThat(rows.get(1).get("target_count")).isEqualTo(1L);
    assertThat(rows.get(2).get("office_id")).isNull();
    assertThat(rows.get(2).get("target_id")).isEqualTo(0L);
    assertThat(rows.get(3).get("target_count")).isEqualTo(1L);
    assertThat(rows).allSatisfy(row -> assertThat(row.get("reason")).isEqualTo("보관 기한 만료"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT contact_encrypted IS NULL AND contact_wiped_at IS NOT NULL FROM"
                    + " legal_guest_privacy_intake WHERE id = ?",
                Boolean.class,
                oldIntake))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT contact_encrypted IS NOT NULL FROM legal_guest_privacy_intake WHERE id = ?",
                Boolean.class,
                recentIntake))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM system_job_run WHERE job_name = 'retention-sweep'",
                String.class))
        .isEqualTo("SUCCESS");

    assertThat(jobService.runScheduled(LegalRetentionJobService.SWEEP_JOB, Instant.now()))
        .isFalse();
    LegalRetentionBatchService.SweepResult again = batchService.sweep();
    assertThat(again.queued()).isZero();
    assertThat(again.refreshed()).isEqualTo(4);
    assertThat(queue()).hasSize(4);
  }

  @Test
  void 비회원_링크_입력_처리기는_사무소의_기한_지난_고객_링크와_제출_시간대만_지운다() throws Exception {
    long link = linkOf(visit.coordinationId());
    SelectionFixture other = confirmed();
    long otherLink = linkOf(other.coordinationId());
    jdbcTemplate.update(
        "UPDATE visit_customer_link SET retention_until = current_date - 1 WHERE id IN (?, ?)",
        link,
        otherLink);
    Instant now = Instant.now();

    Plan plan = targetService.plan(RetentionDeletionTargets.GUEST_LINK_INPUT, officeId, now);
    assertThat(plan.supported()).isTrue();
    assertThat(plan.targetCount()).isEqualTo(1);
    assertThat(plan.holdReasons()).isEmpty();
    Outcome outcome =
        targetService.execute(RetentionDeletionTargets.GUEST_LINK_INPUT, officeId, plan);

    assertThat(outcome.storageResult()).isEqualTo("SUCCESS");
    assertThat(count("SELECT count(*) FROM visit_customer_link WHERE id = ?", link)).isZero();
    assertThat(
            count("SELECT count(*) FROM visit_customer_time_slot WHERE customer_link_id = ?", link))
        .isZero();
    assertThat(count("SELECT count(*) FROM visit_customer_link WHERE id = ?", otherLink))
        .isEqualTo(1);
    assertThat(coordinationStatus(visit.coordinationId())).isEqualTo("CONFIRMED");
  }

  @Test
  void 승인자_개인정보_처리기는_출입승인을_지우지_않고_익명화하며_프로필_연결을_끊는다() {
    long approvalId = visit.base().approvalId();
    expireApproval(approvalId);
    long profile =
        jdbcTemplate.queryForObject(
            "INSERT INTO approver_profile (phone_hash, phone_last4) VALUES (repeat('a', 64),"
                + " '2222') RETURNING id",
            Long.class);
    jdbcTemplate.update(
        "INSERT INTO approver_profile_approval (profile_id, approval_id, status, linked_via,"
            + " decided_at) VALUES (?, ?, 'LINKED', 'PHONE_MATCH', now())",
        profile,
        approvalId);
    Instant now = Instant.now();

    Plan plan = targetService.plan(RetentionDeletionTargets.APPROVER_PERSONAL, officeId, now);
    assertThat(plan.targetCount()).isEqualTo(1);
    targetService.execute(RetentionDeletionTargets.APPROVER_PERSONAL, officeId, plan);

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT approver_name, approver_phone, approver_phone_hash, anonymized_at FROM"
                + " property_access_approval WHERE id = ?",
            approvalId);
    assertThat(row.get("approver_name")).isNull();
    assertThat(row.get("approver_phone")).isNull();
    assertThat(row.get("approver_phone_hash")).isNull();
    assertThat(row.get("anonymized_at")).isNotNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM approver_profile_approval WHERE profile_id = ?",
                String.class,
                profile))
        .isEqualTo("UNLINKED");
    assertThat(
            count(
                "SELECT count(*) FROM approver_auth_event WHERE profile_id = ? AND event_type ="
                    + " 'APPROVAL_UNLINKED' AND reason = 'RETENTION_PURGE'",
                profile))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM visit_approver_condition WHERE approval_id = ?", approvalId))
        .isPositive();
    assertThat(
            targetService
                .plan(RetentionDeletionTargets.APPROVER_PERSONAL, officeId, now)
                .targetCount())
        .isZero();
  }

  @Test
  void 로그인_기록과_카카오_연결_끊기_이력은_규칙_일수가_지난_행만_지운다() {
    long oldLogin = insertLogin("400 days");
    long recentLogin = insertLogin("10 days");
    long oldEvent = insertFinishedUnlink("400 days");
    long recentEvent = insertFinishedUnlink("10 days");
    Instant now = Instant.now();
    long global = RetentionDeletionTargets.GLOBAL_TARGET_ID;

    for (String type :
        List.of(
            RetentionDeletionTargets.LOGIN_HISTORY, RetentionDeletionTargets.KAKAO_UNLINK_EVENT)) {
      Plan plan = targetService.plan(type, global, now);
      assertThat(plan.targetCount()).isEqualTo(1);
      targetService.execute(type, global, plan);
    }

    assertThat(count("SELECT count(*) FROM login_history WHERE id = ?", oldLogin)).isZero();
    assertThat(count("SELECT count(*) FROM login_history WHERE id = ?", recentLogin)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM legal_kakao_unlink_event WHERE id = ?", oldEvent))
        .isZero();
    assertThat(count("SELECT count(*) FROM legal_kakao_unlink_event WHERE id = ?", recentEvent))
        .isEqualTo(1);
  }

  @Test
  void 규칙이_없으면_전체_단위_데이터군은_대상이_없다() {
    jdbcTemplate.update("DELETE FROM legal_retention_rule");
    insertLogin("4000 days");
    assertThat(
            targetService
                .plan(RetentionDeletionTargets.LOGIN_HISTORY, 0L, Instant.now())
                .targetCount())
        .isZero();
  }

  @Test
  void 승인자_익명화는_기한_지난_링크의_토큰과_접속_시각과_안내_확인을_비우고_오래된_인증_IP_변환값을_지운다() {
    jdbcTemplate.update(
        "UPDATE visit_approver_property_link SET retention_until = current_date - 1,"
            + " last_accessed_at = now()");
    long profile =
        jdbcTemplate.queryForObject(
            "INSERT INTO approver_profile (phone_hash, phone_last4) VALUES (repeat('b', 64),"
                + " '3333') RETURNING id",
            Long.class);
    long oldEvent =
        jdbcTemplate.queryForObject(
            "INSERT INTO approver_auth_event (profile_id, event_type, ip_hmac, hmac_key_version,"
                + " occurred_at) VALUES (?, 'LOGIN_SUCCEEDED', repeat('c', 64), 1, now() -"
                + " interval '100 days') RETURNING id",
            Long.class,
            profile);
    long recentEvent =
        jdbcTemplate.queryForObject(
            "INSERT INTO approver_auth_event (profile_id, event_type, ip_hmac, hmac_key_version)"
                + " VALUES (?, 'LOGIN_SUCCEEDED', repeat('d', 64), 1) RETURNING id",
            Long.class,
            profile);

    assertThat(jobService.runScheduled(LegalRetentionJobService.ANONYMIZE_JOB, Instant.now()))
        .isTrue();

    assertThat(
            count(
                "SELECT count(*) FROM visit_approver_property_link WHERE anonymized_at IS NULL OR"
                    + " token_hash IS NOT NULL OR last_accessed_at IS NOT NULL OR revoked_at IS"
                    + " NULL"))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM consent_guest_notice_ack WHERE approver_property_link_id IS"
                    + " NOT NULL"))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM approver_auth_event WHERE id = ? AND ip_hmac IS NULL AND"
                    + " hmac_key_version IS NULL",
                oldEvent))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM approver_auth_event WHERE id = ? AND ip_hmac IS NOT NULL",
                recentEvent))
        .isEqualTo(1);
  }

  @Test
  void 접속_로그_삭제는_삭제_기준일이_지난_행만_지우고_실패한_실행은_같은_키로_재실행한다() {
    for (String purgeAfter : List.of("current_date - 1", "current_date + 1")) {
      jdbcTemplate.update(
          "INSERT INTO legal_public_link_access_log (accessed_at, link_kind, link_id, office_id,"
              + " action_type, http_status, ip_hmac, hmac_key_version, purge_after) VALUES (now(),"
              + " 'CUSTOMER', 1, ?, 'VIEW', 200, repeat('e', 64), 1, "
              + purgeAfter
              + ")",
          officeId);
    }
    long total = count("SELECT count(*) FROM legal_public_link_access_log");
    String key = "legal-link-log-purge:failed";
    jdbcTemplate.update(
        "INSERT INTO system_job_run (job_name, idempotency_key, status, error_code)"
            + " VALUES ('legal-link-log-purge', ?, 'FAILED', 'X')",
        key);
    assertThat(jobService.rerunWork("retention-sweep", key)).isEmpty();
    Runnable work =
        jobService.rerunWork(LegalRetentionJobService.LINK_LOG_PURGE_JOB, key).orElseThrow();

    assertThat(jobRunService.rerunFailed(key, work)).isEqualTo(RerunOutcome.SUCCESS);

    assertThat(count("SELECT count(*) FROM legal_public_link_access_log")).isEqualTo(total - 1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM system_job_run WHERE idempotency_key = ?",
                Integer.class,
                key))
        .isEqualTo(2);
  }
}
