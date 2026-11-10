package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.AppUserBuilder;
import com.sairo.be.support.fixture.OfficeBuilder;
import com.sairo.be.support.fixture.TermsFixtures;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class WithdrawalIntegrationTest extends AuthIntegrationTestBase {

  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;

  private AppUser user;
  private Cookie session;

  @BeforeEach
  void createUser() throws Exception {
    user = saveEmailUser("agent@example.com", "010-1234-5678");
    session = login("agent@example.com", PASSWORD);
  }

  private ResultActions withdraw(Cookie cookie) throws Exception {
    return mockMvc.perform(AuthFlowSupport.withCsrf(delete("/api/me").cookie(cookie)));
  }

  private TestData testData() {
    return new TestData(appUserRepository, officeRepository, officeMembershipRepository);
  }

  private long insertRegistration(long applicantId, String status) {
    String extra = status.equals("SUPPLEMENT_REQUESTED") ? ", '보완', now(), now()" : "";
    String columns =
        status.equals("SUPPLEMENT_REQUESTED")
            ? ", supplement_reason, supplement_due_at, reviewed_at"
            : "";
    return jdbcTemplate.queryForObject(
        "INSERT INTO office_registration (applicant_user_id, status, entity_type,"
            + " registration_authority, real_estate_license_number, business_registration_number,"
            + " requested_office_name, requested_representative_name,"
            + " requested_representative_phone, requested_address,"
            + " applicant_registration_relation"
            + columns
            + ") VALUES (?, ?, 'INDIVIDUAL', '서울', ?, '1234567890', '사무소', '홍대표',"
            + " '010-0000-0000', '서울', '대표'"
            + extra
            + ") RETURNING id",
        Long.class,
        applicantId,
        status,
        "LIC-" + applicantId + status);
  }

  private long insertMembership(Long officeId, Long userId, String role, String status) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO office_membership (office_id, user_id, role, status) VALUES (?, ?, ?, ?)"
            + " RETURNING id",
        Long.class,
        officeId,
        userId,
        role,
        status);
  }

  @Test
  void 탈퇴_미리보기는_상태를_바꾸지_않고_파기_항목과_표시_규칙을_돌려준다() throws Exception {
    mockMvc
        .perform(get("/api/me/withdrawal-preview").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isOwner").value(false))
        .andExpect(jsonPath("$.isLastOwner").value(false))
        .andExpect(jsonPath("$.kakaoLinked").value(false))
        .andExpect(jsonPath("$.destroyedItems[0]").value("이메일"))
        .andExpect(jsonPath("$.destroyedItems.length()").value(5))
        .andExpect(jsonPath("$.retainedItems.length()").value(0))
        .andExpect(
            jsonPath("$.displayRuleNotice").value("담당 업무는 대표님에게 넘어가고 과거 담당 기록에는 탈퇴한 구성원으로 표시됩니다."));
    assertThat(jdbcTemplate.queryForObject("SELECT account_status FROM app_user", String.class))
        .isEqualTo("ACTIVE");
  }

  @Test
  void 보관_항목은_보관_기간_규칙의_값을_그대로_읽는다() throws Exception {
    jdbcTemplate.update(
        "INSERT INTO legal_retention_rule (rule_code, data_group_label, retention_days,"
            + " anchor_event, basis_note) VALUES ('TERMS_AGREEMENT_AFTER_WITHDRAWAL', '약관 동의',"
            + " 1095, 'WITHDRAWN_AT', '검토 대상 법령')");

    mockMvc
        .perform(get("/api/me/withdrawal-preview").cookie(session))
        .andExpect(jsonPath("$.retainedItems.length()").value(1))
        .andExpect(jsonPath("$.retainedItems[0].itemCode").value("TERMS_AGREEMENT"))
        .andExpect(jsonPath("$.retainedItems[0].itemName").value("약관 동의와 철회 이력"))
        .andExpect(jsonPath("$.retainedItems[0].reason").value("동의 사실의 확인과 분쟁 대응"))
        .andExpect(jsonPath("$.retainedItems[0].retentionDays").value(1095))
        .andExpect(jsonPath("$.retainedItems[0].anchorEvent").value("WITHDRAWN_AT"))
        .andExpect(jsonPath("$.retainedItems[0].ruleStatus").value("ASSUMPTION"))
        .andExpect(jsonPath("$.retainedItems[0].reviewTargetLaws[0]").value("개인정보 보호법"))
        .andExpect(jsonPath("$.retainedItems[0].reviewTargetLaws[1]").value("전자상거래법"));
    jdbcTemplate.update("DELETE FROM legal_retention_rule");
  }

  @Test
  void 시드된_보관_규칙으로_문서의_보관_항목_아홉_개와_기간이_나온다() throws Exception {
    jdbcTemplate.execute(
        new ClassPathResource("db/migration/V15__seed_terms_catalog_and_legal_retention_rules.sql")
            .getContentAsString(StandardCharsets.UTF_8));

    mockMvc
        .perform(get("/api/me/withdrawal-preview").cookie(session))
        .andExpect(jsonPath("$.retainedItems.length()").value(9))
        .andExpect(jsonPath("$.retainedItems[0].itemName").value("로그인과 접속 기록"))
        .andExpect(jsonPath("$.retainedItems[0].retentionDays").value(365))
        .andExpect(jsonPath("$.retainedItems[0].reviewTargetLaws[1]").value("통신비밀보호법"))
        .andExpect(jsonPath("$.retainedItems[2].itemName").value("수신 설정 처리 결과 통지와 재확인 발송 이력"))
        .andExpect(jsonPath("$.retainedItems[2].retentionDays").value(1095))
        .andExpect(jsonPath("$.retainedItems[3].retentionDays").value(1825))
        .andExpect(jsonPath("$.retainedItems[4].itemName").value("세금계산서와 현금영수증 발급 기록"))
        .andExpect(jsonPath("$.retainedItems[4].anchorEvent").value("ISSUED_AT"))
        .andExpect(jsonPath("$.retainedItems[7].itemName").value("사무소가 수탁 보관하는 거래계약서"))
        .andExpect(jsonPath("$.retainedItems[7].retentionDays").value(1825))
        .andExpect(jsonPath("$.retainedItems[8].retentionDays").value(1095))
        .andExpect(jsonPath("$.retainedItems[8].reviewTargetLaws[0]").value("공인중개사법"));
    jdbcTemplate.update("DELETE FROM legal_retention_rule");
  }

  @Test
  void 대표는_미리보기에서_마지막_대표로_표시된다() throws Exception {
    Office office = officeRepository.saveAndFlush(OfficeBuilder.anOffice().build());
    insertMembership(office.getId(), user.getId(), "OWNER", "APPROVED");

    mockMvc
        .perform(get("/api/me/withdrawal-preview").cookie(session))
        .andExpect(jsonPath("$.isOwner").value(true))
        .andExpect(jsonPath("$.isLastOwner").value(true));
  }

  @Test
  void 재인증이_없으면_탈퇴는_401이다() throws Exception {
    assertError(withdraw(session), ErrorCode.STEP_UP_REQUIRED);
    assertThat(jdbcTemplate.queryForObject("SELECT account_status FROM app_user", String.class))
        .isEqualTo("ACTIVE");
  }

  @Test
  void 유일한_대표는_409이고_아무것도_바꾸지_않는다() throws Exception {
    Office office = officeRepository.saveAndFlush(OfficeBuilder.anOffice().build());
    insertMembership(office.getId(), user.getId(), "OWNER", "APPROVED");
    grantStepUp(session);

    assertError(withdraw(session), ErrorCode.LAST_OWNER);

    assertThat(jdbcTemplate.queryForObject("SELECT account_status FROM app_user", String.class))
        .isEqualTo("ACTIVE");
    assertThat(jdbcTemplate.queryForObject("SELECT status FROM office_membership", String.class))
        .isEqualTo("APPROVED");
  }

  @Test
  void 탈퇴하면_계정을_탈퇴_상태로_바꾸고_세션을_종료하며_파기_작업을_등록한다() throws Exception {
    Cookie other = login("agent@example.com", PASSWORD);
    grantStepUp(session);

    withdraw(session).andExpect(status().isNoContent());

    Map<String, Object> row =
        jdbcTemplate.queryForMap("SELECT account_status, withdrawn_at FROM app_user");
    assertThat(row.get("account_status")).isEqualTo("WITHDRAWN");
    assertThat(row.get("withdrawn_at")).isNotNull();
    mockMvc.perform(get("/api/auth/context").cookie(session)).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/auth/context").cookie(other)).andExpect(status().isUnauthorized());
    Map<String, Object> queue =
        jdbcTemplate.queryForMap(
            "SELECT target_type, target_id, reason, (scheduled_at - now()) > interval '23 hours'"
                + " AS later FROM deletion_queue");
    assertThat(queue.get("target_type")).isEqualTo("MEMBER");
    assertThat(queue.get("target_id")).isEqualTo(user.getId());
    assertThat(queue.get("reason")).isEqualTo("회원 탈퇴");
    assertThat(queue.get("later")).isEqualTo(true);
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/login",
                "{\"email\":\"agent@example.com\",\"password\":\"" + PASSWORD + "\"}")),
        ErrorCode.INVALID_CREDENTIALS);
  }

  @Test
  void 진행_중_신청과_참여_요청을_취소하고_상태_이력을_남긴다() throws Exception {
    long registrationId = insertRegistration(user.getId(), "PENDING_REVIEW");
    Office office = officeRepository.saveAndFlush(OfficeBuilder.anOffice().build());
    long membershipId = insertMembership(office.getId(), user.getId(), "AGENT", "PENDING");
    grantStepUp(session);

    withdraw(session).andExpect(status().isNoContent());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM office_registration WHERE id = ?",
                String.class,
                registrationId))
        .isEqualTo("CANCELLED");
    Map<String, Object> registrationHistory =
        jdbcTemplate.queryForMap(
            "SELECT from_status, to_status, changed_by_type, reason"
                + " FROM office_registration_status_history");
    assertThat(registrationHistory)
        .containsEntry("from_status", "PENDING_REVIEW")
        .containsEntry("to_status", "CANCELLED")
        .containsEntry("changed_by_type", "SYSTEM")
        .containsEntry("reason", "회원 탈퇴");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM office_membership WHERE id = ?", String.class, membershipId))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT to_status FROM office_membership_status_history", String.class))
        .isEqualTo("CANCELLED");
  }

  @Test
  void 작성_중과_보완_요청_신청도_취소한다() throws Exception {
    long registrationId = insertRegistration(user.getId(), "SUPPLEMENT_REQUESTED");
    grantStepUp(session);

    withdraw(session).andExpect(status().isNoContent());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM office_registration WHERE id = ?",
                String.class,
                registrationId))
        .isEqualTo("CANCELLED");
  }

  @Test
  void 승인된_소속은_해제하고_사무소별_대체_번호를_순서대로_부여한다() throws Exception {
    Office office = officeRepository.saveAndFlush(OfficeBuilder.anOffice().build());
    Office otherOffice = officeRepository.saveAndFlush(OfficeBuilder.anOffice().build());
    AppUser colleague = saveEmailUser("colleague@example.com", null);
    long mine = insertMembership(office.getId(), user.getId(), "AGENT", "APPROVED");
    long old = insertMembership(otherOffice.getId(), user.getId(), "DEPUTY", "CANCELLED");
    long theirs = insertMembership(office.getId(), colleague.getId(), "AGENT", "CANCELLED");
    jdbcTemplate.update("UPDATE office_membership SET withdrawn_alias_no = 1 WHERE id = ?", theirs);
    grantStepUp(session);

    withdraw(session).andExpect(status().isNoContent());

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT id, status, withdrawn_alias_no, revoked_by_type FROM office_membership"
                + " WHERE user_id = ? ORDER BY id",
            user.getId());
    assertThat(rows.get(0)).containsEntry("id", mine).containsEntry("status", "REVOKED");
    assertThat(rows.get(0)).containsEntry("revoked_by_type", "SELF");
    assertThat(rows.get(0).get("withdrawn_alias_no")).isEqualTo(2);
    assertThat(rows.get(1)).containsEntry("id", old);
    assertThat(rows.get(1).get("withdrawn_alias_no")).isEqualTo(1);
  }

  @Test
  void 카카오_계정이면_연결_끊기_작업을_대기_상태로_등록한다() throws Exception {
    AppUser kakao =
        appUserRepository.saveAndFlush(
            AppUserBuilder.anAppUser().kakaoProviderKey("4001001").build());
    Cookie kakaoSession = kakaoSessionFor(kakao);
    grantStepUp(kakaoSession);

    withdraw(kakaoSession).andExpect(status().isNoContent());

    Map<String, Object> event =
        jdbcTemplate.queryForMap(
            "SELECT direction, status, target_key_encrypted, user_id FROM legal_kakao_unlink_event");
    assertThat(event.get("direction")).isEqualTo("OUTBOUND_CALL");
    assertThat(event.get("status")).isEqualTo("PENDING");
    assertThat((String) event.get("target_key_encrypted")).doesNotContain("4001001");
    assertThat(event.get("user_id")).isEqualTo(kakao.getId());
  }

  @Test
  void 이메일_계정은_연결_끊기_작업을_만들지_않는다() throws Exception {
    grantStepUp(session);

    withdraw(session).andExpect(status().isNoContent());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM legal_kakao_unlink_event", Integer.class))
        .isZero();
  }

  @Test
  void 약관_재동의가_필요한_상태에서도_미리보기와_탈퇴는_허용한다() throws Exception {
    TermsFixtures.insert(
        jdbcTemplate,
        "TOS",
        "2026-12-01",
        "서비스 이용약관",
        "TERMS",
        true,
        "2026-06-01T00:00:00Z",
        true,
        "개정");
    grantStepUp(session);

    mockMvc.perform(get("/api/me/withdrawal-preview").cookie(session)).andExpect(status().isOk());
    withdraw(session).andExpect(status().isNoContent());
  }

  @Test
  void 로그인_없이_탈퇴하면_401이다() throws Exception {
    assertError(
        mockMvc.perform(AuthFlowSupport.withCsrf(delete("/api/me"))), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(get("/api/me/withdrawal-preview")), ErrorCode.UNAUTHENTICATED);
    assertThat(testData()).isNotNull();
    assertThat(OfficeMembershipRole.values()).hasSize(3);
  }
}
