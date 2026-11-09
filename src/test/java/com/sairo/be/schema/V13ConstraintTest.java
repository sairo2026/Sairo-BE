package com.sairo.be.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class V13ConstraintTest extends AbstractPostgresSchemaTest {

  private static final String APPROVAL_COLUMNS =
      "(property_id, approver_type, approver_name, approver_phone, valid_from, valid_until,"
          + " source, retention_until, anonymized_at, revoked_at, revocation_reason,"
          + " revocation_recorded_at, revoked_by_user_id)";

  private void skipForeignKeys(Connection conn) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute("SET LOCAL session_replication_role = replica");
    }
  }

  private void execute(Connection conn, String sql) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute(sql);
    }
  }

  private void insertAgreement(Connection conn, String consentPath, String officeId)
      throws SQLException {
    long user = insertUser(conn, "A", uniqueKakaoKey("v13-terms"));
    execute(
        conn,
        "INSERT INTO terms_catalog (terms_code, terms_version, title, category, required,"
            + " document_url, content_hash, effective_at)"
            + " VALUES ('V13_TERMS', 'v1', '약관', 'TERMS', true, 'https://example.com/t',"
            + " repeat('c', 64), now()) ON CONFLICT DO NOTHING");
    execute(
        conn,
        "INSERT INTO terms_agreement (user_id, terms_code, terms_version, agreed, consent_path,"
            + " office_id) VALUES ("
            + user
            + ", 'V13_TERMS', 'v1', true, "
            + (consentPath == null ? "NULL" : "'" + consentPath + "'")
            + ", "
            + officeId
            + ")");
  }

  @Test
  void 동의_경로_없이_약관_동의를_기록하면_NOT_NULL_위반이다() {
    assertConstraintViolation("23502", conn -> insertAgreement(conn, null, "NULL"));
  }

  @Test
  void 허용되지_않은_동의_경로는_CHECK_위반이다() {
    assertConstraintViolation(
        "23514",
        "ck_terms_agreement_consent_path",
        conn -> insertAgreement(conn, "NOT_A_PATH", "NULL"));
  }

  @Test
  void 구매_동의는_사무소_없이_기록하면_CHECK_위반이다() {
    assertConstraintViolation(
        "23514",
        "ck_terms_agreement_office_scope",
        conn -> insertAgreement(conn, "PURCHASE_CONSENT", "NULL"));
  }

  @Test
  void 회원가입_동의는_사무소_없이_기록할_수_있다() throws Exception {
    withRollback(conn -> insertAgreement(conn, "SIGNUP_KAKAO", "NULL"));
  }

  @Test
  void 허용된_취소_사유_코드는_취소_결과에만_기록할_수_있다() throws Exception {
    withRollback(
        conn -> {
          skipForeignKeys(conn);
          insertResultHistory(conn, "CANCELLED", "USER_CANCEL");
          insertResultHistory(conn, "CANCELLED", null);
          insertResultHistory(conn, "NO_SHOW", null);
          assertThat(countRows(conn, "visit_schedule_item_result_history")).isEqualTo(3);
        });
  }

  @Test
  void 허용되지_않은_취소_사유_코드는_CHECK_위반이다() {
    assertConstraintViolation(
        "23514",
        "visit_schedule_item_result_history_cancel_reason_code_check",
        conn -> {
          skipForeignKeys(conn);
          insertResultHistory(conn, "CANCELLED", "UNKNOWN_REASON");
        });
  }

  @Test
  void 취소가_아닌_결과에_취소_사유_코드를_기록하면_CHECK_위반이다() {
    assertConstraintViolation(
        "23514",
        "visit_schedule_item_result_history_check2",
        conn -> {
          skipForeignKeys(conn);
          insertResultHistory(conn, "NO_SHOW", "USER_CANCEL");
        });
  }

  @Test
  void 익명화한_출입승인은_이름과_연락처가_비고_유효기간이_지난_경우에만_허용된다() throws Exception {
    withRollback(
        conn -> {
          skipForeignKeys(conn);
          execute(
              conn,
              "INSERT INTO property_access_approval "
                  + APPROVAL_COLUMNS
                  + " VALUES (1, 'OWNER', NULL, NULL, now() - interval '10 days',"
                  + " now() - interval '5 days', 'DIRECT', current_date, now(), NULL, NULL, NULL,"
                  + " NULL)");
          assertThat(countRows(conn, "property_access_approval")).isEqualTo(1);
        });
  }

  @Test
  void 익명화했는데_이름이_남아_있으면_CHECK_위반이다() {
    assertConstraintViolation(
        "23514",
        "ck_property_access_approval_anonymized",
        conn -> {
          skipForeignKeys(conn);
          execute(
              conn,
              "INSERT INTO property_access_approval "
                  + APPROVAL_COLUMNS
                  + " VALUES (1, 'OWNER', '소유자', NULL, now() - interval '10 days',"
                  + " now() - interval '5 days', 'DIRECT', current_date, now(), NULL, NULL, NULL,"
                  + " NULL)");
        });
  }

  @Test
  void 유효기간_전이고_철회하지_않은_출입승인은_익명화할_수_없다() {
    assertConstraintViolation(
        "23514",
        "ck_property_access_approval_anonymized",
        conn -> {
          skipForeignKeys(conn);
          execute(
              conn,
              "INSERT INTO property_access_approval "
                  + APPROVAL_COLUMNS
                  + " VALUES (1, 'OWNER', NULL, NULL, now() - interval '1 day',"
                  + " now() + interval '5 days', 'DIRECT', current_date, now(), NULL, NULL, NULL,"
                  + " NULL)");
        });
  }

  @Test
  void 익명화하지_않은_DIRECT_출입승인은_연락처가_있어야_한다() {
    assertConstraintViolation(
        "23514",
        "ck_property_access_approval_phone",
        conn -> {
          skipForeignKeys(conn);
          execute(
              conn,
              "INSERT INTO property_access_approval "
                  + APPROVAL_COLUMNS
                  + " VALUES (1, 'OWNER', '소유자', NULL, now() - interval '1 day',"
                  + " now() + interval '5 days', 'DIRECT', current_date, NULL, NULL, NULL, NULL,"
                  + " NULL)");
        });
  }

  @Test
  void 일반_체험_이벤트의_예약_기간이_겹치면_EXCLUDE_제약_위반이다() {
    assertConstraintViolation(
        "23P01",
        "ex_trial_event_general_window",
        conn -> {
          skipForeignKeys(conn);
          insertTrialEvent(conn, "GENERAL", "SCHEDULED", "now()", "now() + interval '10 days'");
          insertTrialEvent(
              conn,
              "GENERAL",
              "SCHEDULED",
              "now() + interval '5 days'",
              "now() + interval '15 days'");
        });
  }

  @Test
  void 일반_체험_이벤트라도_기간이_겹치지_않거나_종료_상태면_허용된다() throws Exception {
    withRollback(
        conn -> {
          skipForeignKeys(conn);
          insertTrialEvent(conn, "GENERAL", "SCHEDULED", "now()", "now() + interval '10 days'");
          insertTrialEvent(
              conn,
              "GENERAL",
              "SCHEDULED",
              "now() + interval '10 days'",
              "now() + interval '20 days'");
          insertTrialEvent(
              conn, "GENERAL", "DRAFT", "now() + interval '1 day'", "now() + interval '5 days'");
          assertThat(countRows(conn, "trial_event")).isEqualTo(3);
        });
  }

  @Test
  void 같은_후보_매물에_진행_중인_방문_신청은_하나만_허용된다() {
    assertConstraintViolation(
        "23505",
        "uq_visit_request_active_candidate",
        conn -> {
          skipForeignKeys(conn);
          insertVisitRequest(conn, "PENDING");
          insertVisitRequest(conn, "SELECTED");
        });
  }

  @Test
  void 같은_후보_매물이라도_이전_신청이_종료됐으면_새_방문_신청을_넣을_수_있다() throws Exception {
    withRollback(
        conn -> {
          skipForeignKeys(conn);
          insertVisitRequest(conn, "WITHDRAWN");
          insertVisitRequest(conn, "PENDING");
          assertThat(countRows(conn, "visit_request")).isEqualTo(2);
        });
  }

  private void insertResultHistory(Connection conn, String result, String cancelReasonCode)
      throws SQLException {
    execute(
        conn,
        "INSERT INTO visit_schedule_item_result_history (schedule_item_id, result, effective_at,"
            + " recorded_by_membership_id, cancel_reason_code) VALUES (1, '"
            + result
            + "', now(), 1, "
            + (cancelReasonCode == null ? "NULL" : "'" + cancelReasonCode + "'")
            + ")");
  }

  private void insertTrialEvent(
      Connection conn, String audience, String status, String startsAt, String endsAt)
      throws SQLException {
    execute(
        conn,
        "INSERT INTO trial_event (name, starts_at, ends_at, status, created_by_operator_id,"
            + " audience) VALUES ('이벤트', "
            + startsAt
            + ", "
            + endsAt
            + ", '"
            + status
            + "', 1, '"
            + audience
            + "')");
  }

  private void insertVisitRequest(Connection conn, String status) throws SQLException {
    execute(
        conn,
        "INSERT INTO visit_request (condition_id, approval_id, property_id, applicant_office_id,"
            + " applicant_membership_id, coordination_id, candidate_property_id,"
            + " requested_start_at, requested_end_at, expected_duration_minutes, status,"
            + " closed_at, property_office_id) VALUES (1, 1, 1, 1, 1, 1, 1, now(),"
            + " now() + interval '1 hour', 30, '"
            + status
            + "', "
            + ("PENDING".equals(status) || "SELECTED".equals(status) ? "NULL" : "now()")
            + ", 1)");
  }

  private long countRows(Connection conn, String table) throws SQLException {
    try (Statement st = conn.createStatement();
        java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
