package com.sairo.be.domain.legal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.OfficeBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

/** 보관 기간 규칙 표의 나머지 데이터군 처리기. 시험 데이터만 만들고 지운다. */
@IntegrationTest
class RetentionGroupPurgerIntegrationTest extends AuthIntegrationTestBase {

  @Autowired private RetentionGroupPurger purger;
  @Autowired private LegalRetentionBatchService batchService;
  @Autowired private DeletionTargetService targetService;
  @Autowired private ObjectStorage objectStorage;
  @Autowired private OfficeRepository officeRepository;

  private AppUser user;

  @BeforeEach
  void seed() throws Exception {
    jdbcTemplate.execute(
        new ClassPathResource("db/migration/V15__seed_terms_catalog_and_legal_retention_rules.sql")
            .getContentAsString(StandardCharsets.UTF_8));
    user = saveEmailUser("retention@example.com", null);
  }

  private long count(String table) {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  private long agreement(String code, boolean withdrawn) {
    if (withdrawn) {
      jdbcTemplate.update(
          "UPDATE app_user SET account_status = 'WITHDRAWN', withdrawn_at = now() - interval '1200"
              + " days' WHERE id = ?",
          user.getId());
    }
    return jdbcTemplate.queryForObject(
        "INSERT INTO terms_agreement (user_id, terms_code, terms_version, agreed, recorded_at,"
            + " consent_path) VALUES (?, ?, '2026-09-01', true, now() - interval '1300 days',"
            + " 'SIGNUP_EMAIL') RETURNING id",
        Long.class,
        user.getId(),
        code);
  }

  @Test
  void 모든_데이터군의_세어_보기와_삭제_문은_빈_데이터에서_오류_없이_실행된다() {
    for (String type : RetentionGroupPurger.TYPES) {
      assertThat(purger.dueCount(type, Instant.now())).as(type).isZero();
      assertThat(purger.execute(type, Instant.now())).as(type).isZero();
    }
  }

  @Test
  void 수신_설정_통지_이력은_기록한_지_규칙_일수가_지난_보낸_행만_지운다() {
    long oldAgreement = agreement("MARKETING", false);
    jdbcTemplate.update(
        "INSERT INTO consent_notice_delivery (user_id, terms_agreement_id, notice_type,"
            + " expressed_at, target_at, due_at, status, sent_at, created_at) VALUES (?, ?,"
            + " 'MARKETING_RESULT', now() - interval '1300 days', now() - interval '1300 days',"
            + " now() - interval '1290 days', 'SENT', now() - interval '1299 days', now() -"
            + " interval '1300 days')",
        user.getId(),
        oldAgreement);
    long recent = agreement("MARKETING", false);
    jdbcTemplate.update(
        "INSERT INTO consent_notice_delivery (user_id, terms_agreement_id, notice_type,"
            + " expressed_at, target_at, due_at, status, created_at) VALUES (?, ?,"
            + " 'MARKETING_RESULT', now(), now(), now() + interval '14 days', 'PENDING', now())",
        user.getId(),
        recent);

    assertThat(purger.dueCount(RetentionGroupPurger.MARKETING_NOTICE_DELIVERY, Instant.now()))
        .isEqualTo(1);
    purger.execute(RetentionGroupPurger.MARKETING_NOTICE_DELIVERY, Instant.now());

    assertThat(count("consent_notice_delivery")).isEqualTo(1);
  }

  @Test
  void 탈퇴_뒤_보관이_끝난_약관_동의는_통지_이력이_참조하는_동안은_지우지_않는다() {
    long free = agreement("TOS", true);
    long referenced = agreement("MARKETING", false);
    jdbcTemplate.update(
        "INSERT INTO consent_notice_delivery (user_id, terms_agreement_id, notice_type,"
            + " expressed_at, target_at, due_at, status, sent_at, created_at) VALUES (?, ?,"
            + " 'MARKETING_RESULT', now() - interval '1300 days', now() - interval '1300 days',"
            + " now() - interval '1290 days', 'SENT', now() - interval '1299 days', now())",
        user.getId(),
        referenced);

    assertThat(purger.dueCount(RetentionGroupPurger.TERMS_AGREEMENT_RETAINED, Instant.now()))
        .isEqualTo(1);
    purger.execute(RetentionGroupPurger.TERMS_AGREEMENT_RETAINED, Instant.now());

    assertThat(jdbcTemplate.queryForList("SELECT id FROM terms_agreement", Long.class))
        .containsExactly(referenced);
    assertThat(free).isNotEqualTo(referenced);
  }

  @Test
  void 지원_문의는_종료_뒤_기한이_지나면_첨부_파일과_함께_지우고_권리_요청이_참조하면_남긴다() {
    long closed = inquiry("CLOSED", "1200 days");
    long open = inquiry("OPEN", null);
    long referenced = inquiry("CLOSED", "1200 days");
    objectStorage.put("inquiry/a", new byte[] {1}, "application/pdf");
    jdbcTemplate.update(
        "INSERT INTO support_inquiry_attachment (inquiry_id, object_key, file_name, media_type,"
            + " size_bytes, uploaded_by_user_id) VALUES (?, 'inquiry/a', 'a.pdf',"
            + " 'application/pdf', 1, ?)",
        closed,
        user.getId());
    jdbcTemplate.update(
        "INSERT INTO privacy_request (support_inquiry_id, request_type, identity_status,"
            + " legal_basis, legal_due_at, internal_due_at, request_scope, status) VALUES (?,"
            + " 'ACCESS', 'VERIFIED', '근거', now(), now(), '범위', 'RECEIVED')",
        referenced);

    assertThat(purger.dueCount(RetentionGroupPurger.SUPPORT_INQUIRY, Instant.now())).isEqualTo(1);
    purger.execute(RetentionGroupPurger.SUPPORT_INQUIRY, Instant.now());

    assertThat(jdbcTemplate.queryForList("SELECT id FROM support_inquiry ORDER BY id", Long.class))
        .containsExactly(open, referenced);
    assertThat(count("support_inquiry_attachment")).isZero();
    assertThat(objectStorage.find("inquiry/a")).isEmpty();
  }

  private long inquiry(String status, String closedAgo) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO support_inquiry (requester_user_id, category, status, content,"
            + " response_due_at, closed_at, created_at) VALUES (?, 'AUTH', ?, '문의',"
            + " now() - interval '1300 days', "
            + (closedAgo == null ? "NULL" : "now() - interval '" + closedAgo + "'")
            + ", now() - interval '1300 days') RETURNING id",
        Long.class,
        user.getId(),
        status);
  }

  private long privacyRequest(String status, String completedAgo) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO privacy_request (request_type, identity_status, legal_basis, legal_due_at,"
            + " internal_due_at, request_scope, status, completed_at) VALUES ('ACCESS',"
            + " 'VERIFIED', '근거', now(), now(), '범위', ?, "
            + (completedAgo == null ? "NULL" : "now() - interval '" + completedAgo + "'")
            + ") RETURNING id",
        Long.class,
        status);
  }

  @Test
  void 권리_요청_이력은_접수_기록을_먼저_지운_뒤_이벤트와_함께_지운다() {
    long converted = privacyRequest("COMPLETED", "1200 days");
    long standalone = privacyRequest("COMPLETED", "1200 days");
    long open = privacyRequest("REVIEWING", null);
    jdbcTemplate.update(
        "INSERT INTO privacy_request_event (privacy_request_id, event_type, new_status,"
            + " action_detail) VALUES (?, 'COMPLETE', 'COMPLETED', '완료')",
        standalone);
    long office = officeRepository.saveAndFlush(OfficeBuilder.anOffice().build()).getId();
    jdbcTemplate.update(
        "INSERT INTO legal_guest_privacy_intake (office_id, link_kind, link_id, request_type,"
            + " requester_role, contact_channel, contact_encrypted, received_at, internal_due_at,"
            + " status, privacy_request_id) VALUES (?, 'CUSTOMER', 1, 'ACCESS', 'SELF', 'PHONE',"
            + " 'x', now() - interval '1300 days', now() - interval '1295 days', 'CONVERTED', ?)",
        office,
        converted);

    assertThat(purger.dueCount(RetentionGroupPurger.PRIVACY_REQUEST_RECORD, Instant.now()))
        .isEqualTo(1);
    assertThat(purger.dueCount(RetentionGroupPurger.GUEST_INTAKE_RECORD, Instant.now()))
        .isEqualTo(1);

    purger.execute(RetentionGroupPurger.GUEST_INTAKE_RECORD, Instant.now());
    purger.execute(RetentionGroupPurger.PRIVACY_REQUEST_RECORD, Instant.now());

    assertThat(count("legal_guest_privacy_intake")).isZero();
    assertThat(count("privacy_request_event")).isZero();
    assertThat(jdbcTemplate.queryForList("SELECT id FROM privacy_request", Long.class))
        .containsExactly(open);
    assertThat(converted).isNotEqualTo(standalone);
  }

  @Test
  void 운영자_감사_기록은_기록한_지_규칙_일수가_지난_행만_지운다() {
    long operator =
        jdbcTemplate.queryForObject(
            "INSERT INTO operator_account (kakao_provider_key, name, status,"
                + " totp_secret_encrypted) VALUES ('retention-op', '운영자', 'ACTIVE', 'x')"
                + " RETURNING id",
            Long.class);
    jdbcTemplate.update(
        "INSERT INTO ops_audit_log (operator_account_id, action_type, created_at) VALUES (?,"
            + " 'TEST', now() - interval '800 days')",
        operator);
    jdbcTemplate.update(
        "INSERT INTO ops_audit_log (operator_account_id, action_type, created_at) VALUES (?,"
            + " 'TEST', now() - interval '10 days')",
        operator);

    assertThat(purger.dueCount(RetentionGroupPurger.OPS_AUDIT_LOG, Instant.now())).isEqualTo(1);
    purger.execute(RetentionGroupPurger.OPS_AUDIT_LOG, Instant.now());

    assertThat(count("ops_audit_log")).isEqualTo(1);
  }

  @Test
  void 보관_기한_정리는_새_데이터군도_파기_큐에_올리고_큐_실행은_결과를_돌려준다() {
    long oldAgreement = agreement("MARKETING", false);
    jdbcTemplate.update(
        "INSERT INTO consent_notice_delivery (user_id, terms_agreement_id, notice_type,"
            + " expressed_at, target_at, due_at, status, sent_at, created_at) VALUES (?, ?,"
            + " 'MARKETING_RESULT', now() - interval '1300 days', now() - interval '1300 days',"
            + " now() - interval '1290 days', 'SENT', now() - interval '1299 days', now() -"
            + " interval '1300 days')",
        user.getId(),
        oldAgreement);

    LegalRetentionBatchService.SweepResult result = batchService.sweep();

    assertThat(result.queued()).isEqualTo(1);
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT target_type, target_id, target_count FROM deletion_queue");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsEntry("target_type", "MARKETING_NOTICE_DELIVERY")
        .containsEntry("target_id", 0L)
        .containsEntry("target_count", 1L);

    DeletionTargetService.Plan plan =
        targetService.plan("MARKETING_NOTICE_DELIVERY", 0L, Instant.now());
    assertThat(plan.supported()).isTrue();
    assertThat(plan.targetCount()).isEqualTo(1);
    DeletionTargetService.Outcome outcome =
        targetService.execute("MARKETING_NOTICE_DELIVERY", 0L, plan);
    assertThat(outcome.storageResult()).isEqualTo(DeletionTargetService.RESULT_SUCCESS);
    assertThat(count("consent_notice_delivery")).isZero();
  }
}
