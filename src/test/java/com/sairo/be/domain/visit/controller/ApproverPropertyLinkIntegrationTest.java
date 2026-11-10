package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.ApproverIntegrationTestBase;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ApproverPropertyLinkIntegrationTest extends ApproverIntegrationTestBase {

  private record Fixture(TestData.Staff owner, Cookie cookie, long propertyId, long approvalId) {}

  private Fixture fixture(String role, int untilDays) throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long propertyId = newProperty(cookie);
    long approvalId = registerApproval(cookie, propertyId, role, 1, untilDays);
    return new Fixture(owner, cookie, propertyId, approvalId);
  }

  private String linkUrl(Fixture f) {
    return approvalsUrl(f.propertyId()) + "/" + f.approvalId() + "/approver-property-link";
  }

  @Test
  void 발급하면_토큰은_응답에만_있고_해시만_저장하며_주소는_승인자_경로다() throws Exception {
    Fixture f = fixture(null, 30);

    JsonNode issued =
        AuthFlowSupport.json(
            mockMvc
                .perform(jsonPostAs(linkUrl(f), "", f.cookie()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.approvalId").value(f.approvalId()))
                .andExpect(jsonPath("$.approvalRole").value("PRIMARY"))
                .andReturn());

    String token = issued.get("token").asString();
    assertThat(issued.get("publicUrl").asString()).endsWith("/approver/" + token);
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT token_hash, issued_by_membership_id, revoked_at FROM"
                + " visit_approver_property_link WHERE id = ?",
            issued.get("linkId").longValue());
    assertThat(row.get("token_hash").toString()).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(row.get("token_hash")).isNotEqualTo(token);
    assertThat(((Number) row.get("issued_by_membership_id")).longValue())
        .isEqualTo(f.owner().membership().getId());
    Integer leaked =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_approver_property_link WHERE token_hash LIKE ?",
            Integer.class,
            "%" + token + "%");
    assertThat(leaked).isZero();
    Duration lifetime =
        Duration.between(
            OffsetDateTime.parse(issued.get("issuedAt").asString()),
            OffsetDateTime.parse(issued.get("expiresAt").asString()));
    assertThat(lifetime).isEqualTo(Duration.ofDays(7));
  }

  @Test
  void 출입승인_유효기간이_짧으면_만료가_유효_종료를_넘지_않는다() throws Exception {
    Fixture f = fixture(null, 2);

    JsonNode issued =
        AuthFlowSupport.json(
            mockMvc
                .perform(jsonPostAs(linkUrl(f), "", f.cookie()))
                .andExpect(status().isCreated())
                .andReturn());

    OffsetDateTime expiresAt = OffsetDateTime.parse(issued.get("expiresAt").asString());
    OffsetDateTime validUntil =
        OffsetDateTime.parse(
            AuthFlowSupport.json(
                    mockMvc
                        .perform(authedGet(approvalsUrl(f.propertyId()), f.cookie()))
                        .andReturn())
                .get("items")
                .get(0)
                .get("validUntil")
                .asString());
    assertThat(expiresAt.toInstant()).isEqualTo(validUntil.toInstant());
  }

  @Test
  void 재발급하면_이전_링크는_폐기되고_활성은_하나다() throws Exception {
    Fixture f = fixture(null, 30);
    String oldToken = issueApproverToken(f.cookie(), f.propertyId(), f.approvalId());

    String newToken = issueApproverToken(f.cookie(), f.propertyId(), f.approvalId());

    assertThat(newToken).isNotEqualTo(oldToken);
    List<Map<String, Object>> links =
        jdbcTemplate.queryForList(
            "SELECT revoked_at FROM visit_approver_property_link WHERE approval_id = ? ORDER BY id",
            f.approvalId());
    assertThat(links).hasSize(2);
    assertThat(links.get(0).get("revoked_at")).isNotNull();
    assertThat(links.get(1).get("revoked_at")).isNull();
    assertError(
        mockMvc.perform(authedGet(conditionsUrl(oldToken), null)), ErrorCode.PUBLIC_LINK_NOT_FOUND);
  }

  @Test
  void 공동_승인자_출입승인도_발급하고_응답에_역할이_있다() throws Exception {
    Fixture f = fixture("CO_APPROVER", 30);

    mockMvc
        .perform(jsonPostAs(linkUrl(f), "", f.cookie()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.approvalRole").value("CO_APPROVER"));
    mockMvc
        .perform(authedGet(linkUrl(f), f.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasActiveLink").value(true))
        .andExpect(jsonPath("$.approvalRole").value("CO_APPROVER"));
  }

  @Test
  void 상태_조회는_토큰_원문이_없고_접속_시각을_보여준다() throws Exception {
    Fixture f = fixture(null, 30);
    mockMvc
        .perform(authedGet(linkUrl(f), f.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasActiveLink").value(false))
        .andExpect(jsonPath("$.linkId").doesNotExist())
        .andExpect(jsonPath("$.approvalRole").value("PRIMARY"));
    String token = issueApproverToken(f.cookie(), f.propertyId(), f.approvalId());
    mockMvc
        .perform(authedGet(linkUrl(f), f.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastAccessedAt").doesNotExist());

    mockMvc.perform(authedGet(conditionsUrl(token), null)).andReturn();

    mockMvc
        .perform(authedGet(linkUrl(f), f.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasActiveLink").value(true))
        .andExpect(jsonPath("$.token").doesNotExist())
        .andExpect(jsonPath("$.publicUrl").doesNotExist())
        .andExpect(jsonPath("$.lastAccessedAt").exists());
  }

  @Test
  void 폐기하면_204이고_이후_토큰은_404이며_활성_링크가_없으면_404다() throws Exception {
    Fixture f = fixture(null, 30);
    assertError(mockMvc.perform(deleteAs(linkUrl(f), f.cookie())), ErrorCode.RESOURCE_NOT_FOUND);
    String token = issueApproverToken(f.cookie(), f.propertyId(), f.approvalId());

    mockMvc.perform(deleteAs(linkUrl(f), f.cookie())).andExpect(status().isNoContent());

    assertError(
        mockMvc.perform(authedGet(conditionsUrl(token), null)), ErrorCode.PUBLIC_LINK_NOT_FOUND);
    mockMvc
        .perform(authedGet(linkUrl(f), f.cookie()))
        .andExpect(jsonPath("$.hasActiveLink").value(false));
    assertError(mockMvc.perform(deleteAs(linkUrl(f), f.cookie())), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 철회된_출입승인과_이미_끝난_출입승인은_발급할_수_없다() throws Exception {
    Fixture revoked = fixture(null, 30);
    jdbcTemplate.update(
        "UPDATE property_access_approval SET revoked_at = now(), revocation_reason = '철회',"
            + " revocation_recorded_at = now(), revoked_by_user_id = ? WHERE id = ?",
        revoked.owner().user().getId(),
        revoked.approvalId());
    assertError(
        mockMvc.perform(jsonPostAs(linkUrl(revoked), "", revoked.cookie())),
        ErrorCode.INVALID_TRANSITION);

    Fixture ended = fixture(null, 30);
    jdbcTemplate.update(
        "UPDATE property_access_approval SET valid_from = now() - interval '5 days', valid_until ="
            + " now() - interval '1 day' WHERE id = ?",
        ended.approvalId());
    assertError(
        mockMvc.perform(jsonPostAs(linkUrl(ended), "", ended.cookie())),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 링크_엔드포인트는_401_403_다른_사무소_404_중개보조원_403을_지킨다() throws Exception {
    Fixture f = fixture(null, 30);
    issueApproverToken(f.cookie(), f.propertyId(), f.approvalId());
    var foreign = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(f.owner().office(), OfficeMembershipRole.DEPUTY);
    var agent = staffIn(f.owner().office(), OfficeMembershipRole.AGENT);
    Cookie plain = kakaoSessionFor(saveEmailUser("nomember3@example.com", "010-1212-3434"));

    assertError(mockMvc.perform(jsonPostAs(linkUrl(f), "", null)), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(authedGet(linkUrl(f), null)), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(deleteAs(linkUrl(f), null)), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(jsonPostAs(linkUrl(f), "", plain)), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(authedGet(linkUrl(f), plain)), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(deleteAs(linkUrl(f), plain)), ErrorCode.FORBIDDEN);
    Cookie deputyCookie = cookieOf(deputy);
    assertError(mockMvc.perform(jsonPostAs(linkUrl(f), "", deputyCookie)), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(authedGet(linkUrl(f), deputyCookie)), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(deleteAs(linkUrl(f), deputyCookie)), ErrorCode.FORBIDDEN);
    Cookie foreignCookie = cookieOf(foreign);
    assertError(
        mockMvc.perform(jsonPostAs(linkUrl(f), "", foreignCookie)), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(authedGet(linkUrl(f), foreignCookie)), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(deleteAs(linkUrl(f), foreignCookie)), ErrorCode.RESOURCE_NOT_FOUND);
    String otherApproval = approvalsUrl(f.propertyId()) + "/987654/approver-property-link";
    assertError(
        mockMvc.perform(jsonPostAs(otherApproval, "", f.cookie())), ErrorCode.RESOURCE_NOT_FOUND);

    mockMvc.perform(jsonPostAs(linkUrl(f), "", cookieOf(agent))).andExpect(status().isCreated());
  }
}
