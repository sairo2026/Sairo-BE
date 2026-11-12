package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitIntegrationTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class CustomerLinkIntegrationTest extends VisitIntegrationTestBase {

  private record Fixture(TestData.Staff owner, Cookie cookie, long customer, long coordinationId) {}

  private Fixture fixture(int startOffsetDays, int endOffsetDays) throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    JsonNode created =
        createCoordination(
            cookie,
            customer,
            null,
            today().plusDays(startOffsetDays),
            today().plusDays(endOffsetDays),
            newProperty(cookie));
    return new Fixture(owner, cookie, customer, created.get("coordinationId").longValue());
  }

  private String linkUrl(Fixture f, String suffix) {
    return "/api/visit-coordinations/" + f.coordinationId() + "/customer-link" + suffix;
  }

  @Test
  void 발급하면_토큰은_응답에만_있고_저장은_해시만_한다() throws Exception {
    Fixture f = fixture(10, 20);

    JsonNode issued =
        AuthFlowSupport.json(
            mockMvc
                .perform(jsonPostAs(linkUrl(f, ""), "", f.cookie()))
                .andExpect(status().isCreated())
                .andReturn());

    String token = issued.get("token").asString();
    assertThat(token).isNotBlank();
    assertThat(issued.get("publicUrl").asString()).endsWith("/visit-responses/" + token);
    assertThat(issued.get("linkId").longValue()).isPositive();
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT token_hash, response_version, revoked_at, retention_until, expires_at FROM"
                + " visit_customer_link WHERE id = ?",
            issued.get("linkId").longValue());
    assertThat(row.get("token_hash")).isNotEqualTo(token);
    assertThat(row.get("token_hash").toString()).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(row.get("response_version")).isEqualTo(0);
    assertThat(row.get("revoked_at")).isNull();
    Integer tokenColumns =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_customer_link WHERE token_hash = ? OR token_hash LIKE ?",
            Integer.class,
            token,
            "%" + token + "%");
    assertThat(tokenColumns).isZero();
    assertThat(
            OffsetDateTime.parse(issued.get("expiresAt").asString())
                .isAfter(OffsetDateTime.parse(issued.get("issuedAt").asString())))
        .isTrue();
  }

  @Test
  void 만료는_발급_후_칠일과_첫_후보_시각_하루_전_중_빠른_쪽이다() throws Exception {
    Fixture far = fixture(30, 30);
    Fixture mid = fixture(3, 10);

    JsonNode farIssued =
        AuthFlowSupport.json(
            mockMvc
                .perform(jsonPostAs(linkUrl(far, ""), "", far.cookie()))
                .andExpect(status().isCreated())
                .andReturn());
    JsonNode midIssued =
        AuthFlowSupport.json(
            mockMvc
                .perform(jsonPostAs(linkUrl(mid, ""), "", mid.cookie()))
                .andExpect(status().isCreated())
                .andReturn());

    Duration farLifetime = lifetime(farIssued);
    assertThat(farLifetime).isEqualTo(Duration.ofDays(7));
    Duration midLifetime = lifetime(midIssued);
    assertThat(midLifetime).isLessThan(Duration.ofDays(7)).isGreaterThan(Duration.ofHours(24));
  }

  @Test
  void 방문_가능_기간이_오늘_시작이면_응답기한은_발급_후_여섯_시간이다() throws Exception {
    Fixture f = fixture(0, 5);

    JsonNode issued =
        AuthFlowSupport.json(
            mockMvc
                .perform(jsonPostAs(linkUrl(f, ""), "", f.cookie()))
                .andExpect(status().isCreated())
                .andReturn());

    assertThat(lifetime(issued)).isEqualTo(Duration.ofHours(6));
    Long retentionDays =
        jdbcTemplate.queryForObject(
            "SELECT retention_until - (expires_at AT TIME ZONE 'Asia/Seoul')::date FROM"
                + " visit_customer_link WHERE id = ?",
            Long.class,
            issued.get("linkId").longValue());
    assertThat(retentionDays).isEqualTo(90L);
  }

  private static Duration lifetime(JsonNode issued) {
    return Duration.between(
        OffsetDateTime.parse(issued.get("issuedAt").asString()).toInstant(),
        OffsetDateTime.parse(issued.get("expiresAt").asString()).toInstant());
  }

  @Test
  void 이미_활성_링크가_있으면_발급은_409이고_발급_전_재발급도_409다() throws Exception {
    Fixture f = fixture(10, 20);

    assertError(
        mockMvc.perform(jsonPostAs(linkUrl(f, "/reissue"), "", f.cookie())),
        ErrorCode.INVALID_TRANSITION);
    issueToken(f.cookie(), f.coordinationId());
    assertError(
        mockMvc.perform(jsonPostAs(linkUrl(f, ""), "", f.cookie())), ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 재발급하면_이전_토큰은_폐기되고_응답_버전과_시간대를_이어받는다() throws Exception {
    Fixture f = fixture(10, 20);
    String oldToken = issueToken(f.cookie(), f.coordinationId());
    String start = today().plusDays(11) + "T10:00:00+09:00";
    String end = today().plusDays(11) + "T12:00:00+09:00";
    mockMvc
        .perform(
            jsonPostAs(
                "/api/public/customer-visit-links/" + oldToken + "/available-times",
                "{\"timeSlots\":[{\"startsAt\":\""
                    + start
                    + "\",\"endsAt\":\""
                    + end
                    + "\"}],\"acknowledgedNoticeVersion\":\""
                    + NOTICE_VERSION
                    + "\"}",
                null))
        .andExpect(status().isOk());

    JsonNode reissued =
        AuthFlowSupport.json(
            mockMvc
                .perform(jsonPostAs(linkUrl(f, "/reissue"), "", f.cookie()))
                .andExpect(status().isOk())
                .andReturn());

    String newToken = reissued.get("token").asString();
    assertThat(newToken).isNotEqualTo(oldToken);
    assertError(
        mockMvc.perform(authedGet("/api/public/customer-visit-links/" + oldToken, null)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
    mockMvc
        .perform(authedGet("/api/public/customer-visit-links/" + newToken, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.responseVersion").value(1))
        .andExpect(jsonPath("$.submittedTimeSlots.length()").value(1));
    List<Map<String, Object>> links =
        jdbcTemplate.queryForList(
            "SELECT id, revoked_at FROM visit_customer_link WHERE coordination_id = ? ORDER BY id",
            f.coordinationId());
    assertThat(links).hasSize(2);
    assertThat(links.get(0).get("revoked_at")).isNotNull();
    assertThat(links.get(1).get("revoked_at")).isNull();
    mockMvc
        .perform(authedGet("/api/visit-coordinations/" + f.coordinationId(), f.cookie()))
        .andExpect(jsonPath("$.customerLink.linkId").value(reissued.get("linkId").longValue()))
        .andExpect(jsonPath("$.customerTimeSlots.length()").value(1));
  }

  @Test
  void 응답을_받을_수_없는_상태에서는_발급과_재발급이_409다() throws Exception {
    Fixture f = fixture(10, 20);
    issueToken(f.cookie(), f.coordinationId());

    for (String statusValue : new String[] {"APPLYING", "CONFIRMED", "CANCELLED"}) {
      setCoordinationStatus(f.coordinationId(), statusValue);
      assertError(
          mockMvc.perform(jsonPostAs(linkUrl(f, "/reissue"), "", f.cookie())),
          ErrorCode.INVALID_TRANSITION);
    }
  }

  @Test
  void 방문_가능_기간이_이미_끝난_조율은_링크를_발급할_수_없다() throws Exception {
    Fixture f = fixture(10, 20);
    jdbcTemplate.update(
        "UPDATE visit_coordination SET period_start_date = current_date - 10,"
            + " period_end_date = current_date - 5 WHERE id = ?",
        f.coordinationId());

    assertError(
        mockMvc.perform(jsonPostAs(linkUrl(f, ""), "", f.cookie())), ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 링크_엔드포인트는_401_403_다른_사무소_404_배정_밖_403을_지킨다() throws Exception {
    Fixture f = fixture(10, 20);
    var foreign = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(f.owner().office(), OfficeMembershipRole.DEPUTY);
    Cookie plain = kakaoSessionFor(saveEmailUser("nomember@example.com", "010-1212-3434"));

    for (String suffix : new String[] {"", "/reissue"}) {
      assertError(
          mockMvc.perform(jsonPostAs(linkUrl(f, suffix), "", null)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(jsonPostAs(linkUrl(f, suffix), "", plain)), ErrorCode.FORBIDDEN);
      assertError(
          mockMvc.perform(jsonPostAs(linkUrl(f, suffix), "", cookieOf(deputy))),
          ErrorCode.FORBIDDEN);
      assertError(
          mockMvc.perform(jsonPostAs(linkUrl(f, suffix), "", cookieOf(foreign))),
          ErrorCode.RESOURCE_NOT_FOUND);
    }
  }

  @Test
  void 배정된_중개보조원은_본인_조율의_고객_링크를_발급하고_재발급한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY);
    addAgentToRoster(owner.office().getId());
    Cookie ownerCookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    long id =
        createCoordination(
                ownerCookie,
                customer,
                deputy.membership().getId(),
                today().plusDays(10),
                today().plusDays(20),
                newProperty(ownerCookie))
            .get("coordinationId")
            .longValue();
    Cookie deputyCookie = cookieOf(deputy);

    mockMvc
        .perform(jsonPostAs("/api/visit-coordinations/" + id + "/customer-link", "", deputyCookie))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            jsonPostAs(
                "/api/visit-coordinations/" + id + "/customer-link/reissue", "", deputyCookie))
        .andExpect(status().isOk());
  }
}
