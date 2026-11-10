package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsIntegrationTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class OperatorOfficeChangeIntegrationTest extends OpsIntegrationTestBase {

  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;

  private Cookie reviewSession;
  private TestData.Staff owner;

  @BeforeEach
  void setUp() {
    reviewSession = operatorSession(createOperator("8101001", "김심사", OperatorRole.REVIEW));
    owner =
        new TestData(appUserRepository, officeRepository, officeMembershipRepository)
            .approvedStaff(OfficeMembershipRole.OWNER);
  }

  private long insertChange(String type, String json) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO office_change_request (office_id, requested_by_membership_id, change_type,"
            + " requested_values, status) VALUES (?, ?, ?, ?, 'PENDING_REVIEW') RETURNING id",
        Long.class,
        owner.office().getId(),
        owner.membership().getId(),
        type,
        json);
  }

  private ResultActions post(String path, String body, Cookie cookie) throws Exception {
    return mockMvc.perform(jsonPost(path, body, cookie));
  }

  @Test
  void 목록은_기본으로_심사_중_신청을_주고_상태_필터와_형식_검사를_한다() throws Exception {
    long pending = insertChange("ADDRESS", "{\"address\":\"새 주소\"}");
    long rejected = insertChange("REPRESENTATIVE", "{\"representativeName\":\"김\"}");
    jdbcTemplate.update(
        "UPDATE office_change_request SET status = 'REJECTED', reviewed_at = now(),"
            + " rejection_reason = '반려' WHERE id = ?",
        rejected);
    mockMvc
        .perform(get("/api/ops/office-change-requests").cookie(reviewSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].requestId").value(pending))
        .andExpect(jsonPath("$.items[0].officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.items[0].requestedByMembershipId").value(owner.membership().getId()))
        .andExpect(jsonPath("$.items[0].requestedValues.address").value("새 주소"));
    mockMvc
        .perform(get("/api/ops/office-change-requests?status=REJECTED").cookie(reviewSession))
        .andExpect(jsonPath("$.items[0].rejectionReason").value("반려"));
    assertError(
        mockMvc.perform(get("/api/ops/office-change-requests?status=X").cookie(reviewSession)),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 주소_변경_승인은_사무소_주소와_버전을_바꾼다() throws Exception {
    long id = insertChange("ADDRESS", "{\"address\":\"서울 새 주소 9\"}");
    post("/api/ops/office-change-requests/" + id + "/approve", "", reviewSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.reviewedAt").exists())
        .andExpect(jsonPath("$.version").value(2));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT address FROM office WHERE id = ?", String.class, owner.office().getId()))
        .isEqualTo("서울 새 주소 9");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ops_audit_log WHERE action_type = 'OFFICE_CHANGE_APPROVED'",
                Long.class))
        .isEqualTo(1L);
  }

  @Test
  void 등록관계와_대표자_변경_승인은_보낸_항목만_반영한다() throws Exception {
    long registration =
        insertChange(
            "REGISTRATION_INFO",
            "{\"registrationAuthority\":\"부산광역시 해운대구\",\"businessRegistrationNumber\":\"5555555555\"}");
    post("/api/ops/office-change-requests/" + registration + "/approve", "", reviewSession)
        .andExpect(status().isOk());
    var office =
        jdbcTemplate.queryForMap(
            "SELECT registration_authority, business_registration_number, real_estate_license_number"
                + " FROM office WHERE id = ?",
            owner.office().getId());
    assertThat(office)
        .containsEntry("registration_authority", "부산광역시 해운대구")
        .containsEntry("business_registration_number", "5555555555")
        .containsEntry("real_estate_license_number", owner.office().getRealEstateLicenseNumber());

    long representative = insertChange("REPRESENTATIVE", "{\"representativeName\":\"김새대표\"}");
    post("/api/ops/office-change-requests/" + representative + "/approve", "", reviewSession)
        .andExpect(status().isOk());
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT representative_name, representative_phone FROM office WHERE id = ?",
                owner.office().getId()))
        .containsEntry("representative_name", "김새대표")
        .containsEntry("representative_phone", "010-0000-0000");
  }

  @Test
  void 등록관계_변경이_다른_사무소의_개설등록번호와_겹치면_승인은_409다() throws Exception {
    var other =
        new TestData(appUserRepository, officeRepository, officeMembershipRepository)
            .approvedStaff(OfficeMembershipRole.OWNER);
    long id =
        insertChange(
            "REGISTRATION_INFO",
            "{\"registrationAuthority\":\""
                + other.office().getRegistrationAuthority()
                + "\",\"realEstateLicenseNumber\":\""
                + other.office().getRealEstateLicenseNumber()
                + "\"}");
    assertError(
        post("/api/ops/office-change-requests/" + id + "/approve", "", reviewSession),
        ErrorCode.OFFICE_ALREADY_EXISTS);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM office_change_request WHERE id = ?", String.class, id))
        .isEqualTo("PENDING_REVIEW");
  }

  @Test
  void 반려는_사유를_남기고_사무소_정보를_바꾸지_않는다() throws Exception {
    long id = insertChange("ADDRESS", "{\"address\":\"새 주소\"}");
    post(
            "/api/ops/office-change-requests/" + id + "/reject",
            "{\"reason\":\"증빙이 부족합니다\"}",
            reviewSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.reviewedAt").exists());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT rejection_reason FROM office_change_request WHERE id = ?",
                String.class,
                id))
        .isEqualTo("증빙이 부족합니다");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT address FROM office WHERE id = ?", String.class, owner.office().getId()))
        .isEqualTo("서울특별시 종로구 테스트로 1");
  }

  @Test
  void 승인과_반려는_이미_처리됐으면_409이고_없으면_404이고_반려_사유가_비면_400이다() throws Exception {
    long id = insertChange("ADDRESS", "{\"address\":\"새 주소\"}");
    String base = "/api/ops/office-change-requests/" + id;
    assertError(
        post(base + "/reject", "{\"reason\":\"\"}", reviewSession), ErrorCode.INVALID_REQUEST);
    assertError(post(base + "/reject", "{}", reviewSession), ErrorCode.INVALID_REQUEST);
    post(base + "/approve", "", reviewSession).andExpect(status().isOk());
    assertError(post(base + "/approve", "", reviewSession), ErrorCode.INVALID_TRANSITION);
    assertError(
        post(base + "/reject", "{\"reason\":\"사유\"}", reviewSession), ErrorCode.INVALID_TRANSITION);
    assertError(
        post("/api/ops/office-change-requests/99999/approve", "", reviewSession),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        post("/api/ops/office-change-requests/99999/reject", "{\"reason\":\"사유\"}", reviewSession),
        ErrorCode.RESOURCE_NOT_FOUND);
  }
}
