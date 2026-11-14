package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.GrantType;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsIntegrationTestBase;
import com.sairo.be.support.fixture.AppUserBuilder;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class ArchiveGrantIntegrationTest extends OpsIntegrationTestBase {

  private static final String URL = "/api/archive-grants";

  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;

  private TestData data() {
    return new TestData(appUserRepository, officeRepository, officeMembershipRepository);
  }

  private static String body(long officeId, long userId, String reference) {
    return "{\"officeId\":"
        + officeId
        + ",\"grantedToUserId\":"
        + userId
        + ",\"contractReference\":\""
        + reference
        + "\"}";
  }

  private Cookie operator() {
    return operatorSession(
        createOperator("op-" + System.nanoTime(), "운영자", OperatorRole.SECURITY_ADMIN));
  }

  @Test
  void 운영자가_보관권한을_발급하면_201과_권한_정보를_준다() throws Exception {
    TestData.Staff owner = data().approvedStaff(OfficeMembershipRole.OWNER);
    var result =
        mockMvc
            .perform(
                jsonPost(
                    URL,
                    body(owner.office().getId(), owner.user().getId(), "ARCHIVE-2026-001"),
                    operator()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.grantId").isNumber())
            .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
            .andExpect(jsonPath("$.expiresAt").value(nullValue()))
            .andExpect(jsonPath("$.revoked").value(false))
            .andReturn();
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT office_id, granted_to_user_id, contract_reference, expires_at, terminated_at"
                + " FROM archive_grant");
    assertThat(row.get("office_id")).isEqualTo(owner.office().getId());
    assertThat(row.get("granted_to_user_id")).isEqualTo(owner.user().getId());
    assertThat(row.get("contract_reference")).isEqualTo("ARCHIVE-2026-001");
    assertThat(row.get("expires_at")).isNull();
    assertThat(row.get("terminated_at")).isNull();
    assertThat(result.getResponse().getContentAsString()).contains("grantId");
  }

  @Test
  void 대상_사무소나_사용자가_없으면_404_RESOURCE_NOT_FOUND이다() throws Exception {
    TestData.Staff owner = data().approvedStaff(OfficeMembershipRole.OWNER);
    Cookie operator = operator();
    assertError(
        mockMvc.perform(jsonPost(URL, body(999_999, owner.user().getId(), "REF"), operator)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPost(URL, body(owner.office().getId(), 999_999, "REF"), operator)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM archive_grant", Integer.class))
        .isZero();
  }

  @Test
  void 필수_필드가_없거나_계약_참조번호가_비어_있으면_400이다() throws Exception {
    Cookie operator = operator();
    assertError(
        mockMvc.perform(jsonPost(URL, "{\"officeId\":1}", operator)), ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(jsonPost(URL, body(1, 1, ""), operator)), ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 운영자가_아니면_발급할_수_없다() throws Exception {
    TestData.Staff owner = data().approvedStaff(OfficeMembershipRole.OWNER);
    String payload = body(owner.office().getId(), owner.user().getId(), "REF");
    assertError(mockMvc.perform(jsonPost(URL, payload)), ErrorCode.UNAUTHENTICATED);
    Cookie member =
        sessions.cookie(
            sessions.createMemberSession(owner.principal(), Instant.now(), Instant.now()));
    assertError(mockMvc.perform(jsonPost(URL, payload, member)), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 보관권한_세션은_권한_상태를_보고_종료된_권한은_403_ARCHIVE_GRANT_INVALID이다() throws Exception {
    TestData.Staff owner = data().approvedStaff(OfficeMembershipRole.OWNER);
    AppUser holder = appUserRepository.saveAndFlush(AppUserBuilder.anAppUser().build());
    long grantId =
        jdbcTemplate.queryForObject(
            "INSERT INTO archive_grant (office_id, granted_to_user_id, contract_reference) VALUES (?, ?, 'REF')"
                + " RETURNING id",
            Long.class,
            owner.office().getId(),
            holder.getId());
    Cookie cookie =
        sessions.cookie(
            sessions.createGrantSession(
                new com.sairo.be.global.security.GrantPrincipal(
                    holder.getId(), grantId, GrantType.ARCHIVE, owner.office().getId()),
                Instant.now()));
    mockMvc
        .perform(get(URL + "/current").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grantId").value(grantId))
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.expiresAt").value(nullValue()))
        .andExpect(jsonPath("$.revoked").value(false));
    jdbcTemplate.update(
        "UPDATE archive_grant SET terminated_at = now(), terminated_reason = '종료' WHERE id = ?",
        grantId);
    assertError(
        mockMvc.perform(get(URL + "/current").cookie(cookie)), ErrorCode.ARCHIVE_GRANT_INVALID);
  }

  @Test
  void 보관권한_조회는_비로그인_일반_구성원_반환권한_세션을_거절한다() throws Exception {
    TestData.Staff owner = data().approvedStaff(OfficeMembershipRole.OWNER);
    assertError(mockMvc.perform(get(URL + "/current")), ErrorCode.UNAUTHENTICATED);
    Cookie member =
        sessions.cookie(
            sessions.createMemberSession(owner.principal(), Instant.now(), Instant.now()));
    assertError(
        mockMvc.perform(get(URL + "/current").cookie(member)), ErrorCode.ARCHIVE_GRANT_INVALID);
    Cookie returnSession =
        sessions.cookie(
            sessions.createGrantSession(
                new com.sairo.be.global.security.GrantPrincipal(
                    owner.user().getId(), 1L, GrantType.RETURN, owner.office().getId()),
                Instant.now()));
    assertError(
        mockMvc.perform(get(URL + "/current").cookie(returnSession)),
        ErrorCode.ARCHIVE_GRANT_INVALID);
  }
}
