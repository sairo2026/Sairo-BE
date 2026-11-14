package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class AuthContextIntegrationTest extends AuthIntegrationTestBase {

  private AppUser user;
  private Cookie session;

  @BeforeEach
  void createUser() throws Exception {
    user = saveEmailUser("agent@example.com", null);
    session = login("agent@example.com", PASSWORD);
  }

  private long insertOffice() {
    return jdbcTemplate.queryForObject(
        "INSERT INTO office (name, entity_type, registration_authority,"
            + " real_estate_license_number, business_registration_number, representative_name,"
            + " representative_phone, address) VALUES ('테스트사무소', 'INDIVIDUAL', '서울 종로구',"
            + " 'L-' || nextval(pg_get_serial_sequence('office','id')), '1234567890', '홍대표',"
            + " '010-0000-0000', '서울') RETURNING id",
        Long.class);
  }

  private long insertMembership(
      long officeId, String role, String status, String revokedBy, String rejectionReason) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO office_membership (office_id, user_id, role, status, revoked_by_type,"
            + " rejection_reason, revoked_at) VALUES (?, ?, ?, ?, ?, ?,"
            + " CASE WHEN ?::text IS NULL THEN NULL ELSE now() END) RETURNING id",
        Long.class,
        officeId,
        user.getId(),
        role,
        status,
        revokedBy,
        rejectionReason,
        revokedBy);
  }

  private ResultActions context() throws Exception {
    return mockMvc.perform(get("/api/auth/context").cookie(session));
  }

  private void insertRegistrationRow(String status, Long officeId) {
    String columns =
        "applicant_user_id, status, entity_type, registration_authority,"
            + " real_estate_license_number, business_registration_number, requested_office_name,"
            + " requested_representative_name, requested_representative_phone, requested_address,"
            + " applicant_registration_relation";
    String values =
        "?, ?, 'INDIVIDUAL', '서울', ?, '1234567890', '사무소', '홍대표', '010-0000-0000', '서울', '대표'";
    switch (status) {
      case "SUPPLEMENT_REQUESTED" ->
          jdbcTemplate.update(
              "INSERT INTO office_registration ("
                  + columns
                  + ", supplement_reason, supplement_due_at, reviewed_at) VALUES ("
                  + values
                  + ", '보완', now(), now())",
              user.getId(),
              status,
              "LIC-" + status);
      case "REJECTED" ->
          jdbcTemplate.update(
              "INSERT INTO office_registration ("
                  + columns
                  + ", rejection_reason, reviewed_at) VALUES ("
                  + values
                  + ", '반려 사유', now())",
              user.getId(),
              status,
              "LIC-" + status);
      case "REVOKED" ->
          jdbcTemplate.update(
              "INSERT INTO office_registration ("
                  + columns
                  + ", revocation_reason, reviewed_at, revoked_at, resulting_office_id) VALUES ("
                  + values
                  + ", '회수', now(), now(), ?)",
              user.getId(),
              status,
              "LIC-" + status,
              officeId);
      default ->
          jdbcTemplate.update(
              "INSERT INTO office_registration (" + columns + ") VALUES (" + values + ")",
              user.getId(),
              status,
              "LIC-" + status);
    }
  }

  private void insertSubscription(
      long officeId, String status, Instant pastDueSince, Instant endedAt) {
    jdbcTemplate.update(
        "INSERT INTO subscription (office_id, plan, status, past_due_since, ended_at)"
            + " VALUES (?, 'SOLO', ?, ?, ?)",
        officeId,
        status,
        pastDueSince == null ? null : Timestamp.from(pastDueSince),
        endedAt == null ? null : Timestamp.from(endedAt));
  }

  @Test
  void 인증_없이_조회하면_401이다() throws Exception {
    assertError(mockMvc.perform(get("/api/auth/context")), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 신청과_소속이_없으면_PRE_VERIFICATION이다() throws Exception {
    context()
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessCondition").value("PRE_VERIFICATION"))
        .andExpect(jsonPath("$.officeId").doesNotExist())
        .andExpect(jsonPath("$.reconsentRequired").value(false));
  }

  @Test
  void 작성_중인_신청은_PRE_VERIFICATION이고_신청_ID를_채운다() throws Exception {
    insertRegistrationRow("DRAFT", null);
    Long id = jdbcTemplate.queryForObject("SELECT id FROM office_registration", Long.class);

    context()
        .andExpect(jsonPath("$.accessCondition").value("PRE_VERIFICATION"))
        .andExpect(jsonPath("$.pendingRegistrationId").value(id));
  }

  @Test
  void 심사_중과_보완_요청_신청은_OFFICE_UNDER_REVIEW다() throws Exception {
    insertRegistrationRow("PENDING_REVIEW", null);
    context().andExpect(jsonPath("$.accessCondition").value("OFFICE_UNDER_REVIEW"));

    jdbcTemplate.update("DELETE FROM office_registration");
    insertRegistrationRow("SUPPLEMENT_REQUESTED", null);
    context().andExpect(jsonPath("$.accessCondition").value("OFFICE_UNDER_REVIEW"));
  }

  @Test
  void 참여_요청_대기는_PRE_VERIFICATION이고_요청_ID를_채운다() throws Exception {
    long officeId = insertOffice();
    long membershipId = insertMembership(officeId, "AGENT", "PENDING", null, null);

    context()
        .andExpect(jsonPath("$.accessCondition").value("PRE_VERIFICATION"))
        .andExpect(jsonPath("$.pendingMembershipId").value(membershipId));
  }

  @Test
  void 반려된_신청과_거절된_참여는_미해결_사유를_담는다() throws Exception {
    insertRegistrationRow("REJECTED", null);
    context()
        .andExpect(jsonPath("$.accessCondition").value("PRE_VERIFICATION"))
        .andExpect(jsonPath("$.unresolvedRejection.type").value("REGISTRATION_REJECTED"))
        .andExpect(jsonPath("$.unresolvedRejection.reason").value("반려 사유"));

    jdbcTemplate.update("DELETE FROM office_registration");
    long officeId = insertOffice();
    insertMembership(officeId, "AGENT", "REJECTED", null, "정원 초과");
    context()
        .andExpect(jsonPath("$.unresolvedRejection.type").value("MEMBERSHIP_REJECTED"))
        .andExpect(jsonPath("$.unresolvedRejection.reason").value("정원 초과"));
  }

  @Test
  void 승인된_소속에_구독이_없거나_결제_전이면_VERIFIED_UNPAID다() throws Exception {
    long officeId = insertOffice();
    insertMembership(officeId, "OWNER", "APPROVED", null, null);

    context()
        .andExpect(jsonPath("$.accessCondition").value("VERIFIED_UNPAID"))
        .andExpect(jsonPath("$.officeId").value(officeId))
        .andExpect(jsonPath("$.role").value("OWNER"));

    insertSubscription(officeId, "PRE_PAYMENT", null, null);
    context().andExpect(jsonPath("$.accessCondition").value("VERIFIED_UNPAID"));
  }

  @Test
  void 구독이_정상이면_ACTIVE다() throws Exception {
    long officeId = insertOffice();
    insertMembership(officeId, "AGENT", "APPROVED", null, null);
    insertSubscription(officeId, "ACTIVE", null, null);

    context()
        .andExpect(jsonPath("$.accessCondition").value("ACTIVE"))
        .andExpect(jsonPath("$.role").value("AGENT"))
        .andExpect(jsonPath("$.graceUntil").doesNotExist());
  }

  @Test
  void 미납_유예_중이면_ACTIVE이고_유예_종료_시각은_8일_뒤다() throws Exception {
    long officeId = insertOffice();
    insertMembership(officeId, "AGENT", "APPROVED", null, null);
    Instant since = Instant.now().minus(2, ChronoUnit.DAYS);
    insertSubscription(officeId, "PAST_DUE_GRACE", since, null);

    context()
        .andExpect(jsonPath("$.accessCondition").value("ACTIVE"))
        .andExpect(jsonPath("$.graceUntil").isNotEmpty());
  }

  @Test
  void 미납_제한과_종료_후_반환_기간은_PAYMENT_OVERDUE다() throws Exception {
    long officeId = insertOffice();
    insertMembership(officeId, "OWNER", "APPROVED", null, null);
    insertSubscription(
        officeId, "PAST_DUE_RESTRICTED", Instant.now().minus(9, ChronoUnit.DAYS), null);
    context().andExpect(jsonPath("$.accessCondition").value("PAYMENT_OVERDUE"));

    jdbcTemplate.update("DELETE FROM subscription");
    insertSubscription(officeId, "ENDED", null, Instant.now().minus(3, ChronoUnit.DAYS));
    context()
        .andExpect(jsonPath("$.accessCondition").value("PAYMENT_OVERDUE"))
        .andExpect(jsonPath("$.returnPeriodEndsAt").isNotEmpty());
  }

  @Test
  void 반환_기간이_끝나면_RETURN_EXPIRED다() throws Exception {
    long officeId = insertOffice();
    insertMembership(officeId, "OWNER", "APPROVED", null, null);
    insertSubscription(officeId, "ENDED", null, Instant.now().minus(31, ChronoUnit.DAYS));

    context()
        .andExpect(jsonPath("$.accessCondition").value("RETURN_EXPIRED"))
        .andExpect(jsonPath("$.returnPeriodEndsAt").doesNotExist());
  }

  @Test
  void 구독이_인증_중단이면_AUTH_REVOKED와_재인증_중단_사유다() throws Exception {
    long officeId = insertOffice();
    insertMembership(officeId, "OWNER", "APPROVED", null, null);
    insertSubscription(officeId, "SUSPENDED", null, null);

    context()
        .andExpect(jsonPath("$.accessCondition").value("AUTH_REVOKED"))
        .andExpect(jsonPath("$.restrictionReason").value("REVERIFICATION_SUSPENDED"));
  }

  @Test
  void 사업자_인증이_회수되면_AUTH_REVOKED와_회수_사유다() throws Exception {
    long officeId = insertOffice();
    insertMembership(officeId, "OWNER", "APPROVED", null, null);
    insertRegistrationRow("REVOKED", officeId);

    context()
        .andExpect(jsonPath("$.accessCondition").value("AUTH_REVOKED"))
        .andExpect(jsonPath("$.restrictionReason").value("REVOKED"));
  }

  @Test
  void 소속이_해제되면_REMOVED이고_사유를_구분한다() throws Exception {
    long officeId = insertOffice();
    insertMembership(officeId, "AGENT", "REVOKED", "SELF", null);
    context()
        .andExpect(jsonPath("$.accessCondition").value("REMOVED"))
        .andExpect(jsonPath("$.removalReason").value("RESIGNED"));

    jdbcTemplate.update("DELETE FROM office_membership");
    insertMembership(officeId, "AGENT", "REVOKED", "OWNER", null);
    context().andExpect(jsonPath("$.removalReason").value("REMOVED"));

    jdbcTemplate.update("DELETE FROM office_membership");
    insertMembership(officeId, "AGENT", "SUSPENDED", null, null);
    context()
        .andExpect(jsonPath("$.accessCondition").value("REMOVED"))
        .andExpect(jsonPath("$.removalReason").value("SUSPENDED"));
  }

  @Test
  void 다른_사무소_참여_요청이_진행_중이면_이전_해제_기록보다_우선한다() throws Exception {
    long officeId = insertOffice();
    insertMembership(officeId, "AGENT", "REVOKED", "OWNER", null);
    long otherOffice = insertOffice();
    insertMembership(otherOffice, "AGENT", "PENDING", null, null);

    context().andExpect(jsonPath("$.accessCondition").value("PRE_VERIFICATION"));
  }
}
