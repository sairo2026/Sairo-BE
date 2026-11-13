package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@IntegrationTest
class RawAccessWindowIntegrationTest extends OpsConsoleTestBase {

  private static final String REQUESTS = "/api/ops/raw-access-requests";

  private Cookie requesterSession;
  private Cookie privacySession;
  private TestData.Staff owner;

  @BeforeEach
  void setUp() {
    requesterSession = sessionOf(operator("박지원", OperatorRole.SUPPORT));
    privacySession = sessionOf(operator("최개인", OperatorRole.PRIVACY));
    owner = staff(OfficeMembershipRole.OWNER);
  }

  private String body(String minutesField) {
    return "{\"requestType\":\"RAW_VIEW\",\"targetType\":\"OFFICE\",\"targetId\":"
        + owner.office().getId()
        + ",\"purpose\":\"고객 문의 대응\",\"scope\":\"READ_ONLY\","
        + minutesField
        + "\"basisType\":\"LEGAL_BASIS\",\"basisNote\":\"법적 근거 확인\"}";
  }

  private long create(String minutesField) throws Exception {
    String response =
        post(REQUESTS, body(minutesField), requesterSession)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(response.replaceAll("(?s).*\"requestId\":(\\d+).*", "$1"));
  }

  private void approveAndExecute(long requestId) throws Exception {
    post(REQUESTS + "/" + requestId + "/approve", "", privacySession).andExpect(status().isOk());
    stepUp(requesterSession);
    post(REQUESTS + "/" + requestId + "/execute", "", requesterSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expiresAt").isNotEmpty());
  }

  private long grantsWithWindow(long requestId, int minutes) {
    return count(
        "SELECT count(*) FROM ops_raw_access_grant WHERE approval_request_id = ? AND expires_at ="
            + " executed_at + make_interval(mins => ?)",
        requestId,
        minutes);
  }

  @Test
  void 요청한_분을_저장하고_실행하면_접근_창을_그_분만큼_연다() throws Exception {
    long id = create("\"requestedExpiresMinutes\":15,");

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT requested_expires_minutes FROM ops_approval_request WHERE id = ?",
                Integer.class,
                id))
        .isEqualTo(15);
    approveAndExecute(id);

    assertThat(grantsWithWindow(id, 15)).isEqualTo(1);
    assertThat(grantsWithWindow(id, 60)).isZero();
  }

  @Test
  void 요청한_분이_없으면_저장하지_않고_접근_창은_60분이다() throws Exception {
    long id = create("");

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT requested_expires_minutes FROM ops_approval_request WHERE id = ?",
                Integer.class,
                id))
        .isNull();
    approveAndExecute(id);

    assertThat(grantsWithWindow(id, 60)).isEqualTo(1);
  }

  @Test
  void 요청한_분은_상한인_60분까지_받고_범위를_벗어나면_400이다() throws Exception {
    long id = create("\"requestedExpiresMinutes\":60,");
    approveAndExecute(id);
    assertThat(grantsWithWindow(id, 60)).isEqualTo(1);

    assertFieldError(
        post(REQUESTS, body("\"requestedExpiresMinutes\":0,"), requesterSession),
        "requestedExpiresMinutes",
        "OUT_OF_RANGE");
    assertFieldError(
        post(REQUESTS, body("\"requestedExpiresMinutes\":61,"), requesterSession),
        "requestedExpiresMinutes",
        "OUT_OF_RANGE");
  }
}
