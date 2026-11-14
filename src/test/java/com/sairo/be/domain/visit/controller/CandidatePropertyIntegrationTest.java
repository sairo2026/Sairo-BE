package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class CandidatePropertyIntegrationTest extends VisitIntegrationTestBase {

  private record Fixture(
      com.sairo.be.support.fixture.TestData.Staff owner,
      Cookie cookie,
      long customer,
      long coordinationId,
      List<Long> candidates) {}

  private Fixture fixture(int candidateCount) throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    List<Long> properties = new ArrayList<>();
    for (int i = 0; i < candidateCount; i++) {
      properties.add(newProperty(cookie));
    }
    JsonNode created =
        AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(
                        "/api/visit-coordinations",
                        createBody(customer, null, today(), today().plusDays(5), properties),
                        cookie))
                .andExpect(status().isCreated())
                .andReturn());
    return new Fixture(
        owner, cookie, customer, created.get("coordinationId").longValue(), properties);
  }

  private String url(Fixture f) {
    return "/api/visit-coordinations/" + f.coordinationId() + "/candidate-properties";
  }

  private List<Long> orderedProperties(long coordinationId) {
    return jdbcTemplate.queryForList(
        "SELECT property_id FROM visit_candidate_property WHERE coordination_id = ? ORDER BY"
            + " sort_order",
        Long.class,
        coordinationId);
  }

  @Test
  void 후보를_추가하면_마지막_순서로_붙고_부모_버전이_오른다() throws Exception {
    Fixture f = fixture(1);
    long added = newProperty(f.cookie());
    long before = coordinationVersion(f.coordinationId());

    JsonNode response =
        AuthFlowSupport.json(
            mockMvc
                .perform(jsonPostAs(url(f), "{\"propertyId\":" + added + "}", f.cookie()))
                .andExpect(status().isCreated())
                .andReturn());

    assertThat(response.get("sortOrder").intValue()).isEqualTo(2);
    assertThat(response.get("propertyId").longValue()).isEqualTo(added);
    assertThat(response.get("coordinationId").longValue()).isEqualTo(f.coordinationId());
    assertThat(response.get("officeId").longValue()).isEqualTo(f.owner().office().getId());
    assertThat(response.get("version").longValue()).isEqualTo(before + 1);
    assertThat(coordinationVersion(f.coordinationId())).isEqualTo(before + 1);
  }

  @Test
  void 순서를_지정하면_뒤_후보를_밀어_재정렬한다() throws Exception {
    Fixture f = fixture(2);
    long added = newProperty(f.cookie());

    mockMvc
        .perform(jsonPostAs(url(f), "{\"propertyId\":" + added + ",\"sortOrder\":1}", f.cookie()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sortOrder").value(1));

    assertThat(orderedProperties(f.coordinationId()))
        .containsExactly(added, f.candidates().get(0), f.candidates().get(1));
    List<Integer> orders =
        jdbcTemplate.queryForList(
            "SELECT sort_order FROM visit_candidate_property WHERE coordination_id = ? ORDER BY"
                + " sort_order",
            Integer.class,
            f.coordinationId());
    assertThat(orders).containsExactly(1, 2, 3);
  }

  @Test
  void 스무_개가_찬_조율에_추가하면_422다() throws Exception {
    Fixture f = fixture(20);
    long extra = newProperty(f.cookie());

    assertError(
        mockMvc.perform(jsonPostAs(url(f), "{\"propertyId\":" + extra + "}", f.cookie())),
        ErrorCode.CANDIDATE_LIMIT_EXCEEDED);
    assertThat(orderedProperties(f.coordinationId())).hasSize(20);
  }

  @Test
  void 중복_다른_사무소_보관_없는_매물과_잘못된_요청을_거절한다() throws Exception {
    Fixture f = fixture(1);
    var foreignOwner = newStaff(OfficeMembershipRole.OWNER);
    long foreign = newProperty(cookieOf(foreignOwner));
    long archived = newProperty(f.cookie());
    archive(archived);

    assertFieldError(
        mockMvc.perform(
            jsonPostAs(url(f), "{\"propertyId\":" + f.candidates().get(0) + "}", f.cookie())),
        "propertyId",
        "INVALID_VALUE");
    for (long propertyId : new long[] {foreign, archived, 987_654L}) {
      assertError(
          mockMvc.perform(jsonPostAs(url(f), "{\"propertyId\":" + propertyId + "}", f.cookie())),
          ErrorCode.CANDIDATE_NOT_ALLOWED);
    }
    assertFieldError(
        mockMvc.perform(jsonPostAs(url(f), "{}", f.cookie())), "propertyId", "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                url(f),
                "{\"propertyId\":" + newProperty(f.cookie()) + ",\"sortOrder\":0}",
                f.cookie())),
        "sortOrder",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(jsonPostAs(url(f), "{\"propertyId\":1,\"other\":2}", f.cookie())),
        "other",
        "NOT_ALLOWED");
  }

  @Test
  void 취소되었거나_끝난_조율에는_후보를_추가할_수_없다() throws Exception {
    Fixture f = fixture(1);
    long extra = newProperty(f.cookie());
    for (String status : new String[] {"CANCELLED", "CONFIRMED", "VISIT_COMPLETED"}) {
      setCoordinationStatus(f.coordinationId(), status);
      assertError(
          mockMvc.perform(jsonPostAs(url(f), "{\"propertyId\":" + extra + "}", f.cookie())),
          ErrorCode.INVALID_TRANSITION);
    }
  }

  @Test
  void 조율_버전이_다르면_추가와_제거가_버전_충돌이다() throws Exception {
    Fixture f = fixture(2);
    long extra = newProperty(f.cookie());
    long version = coordinationVersion(f.coordinationId());

    mockMvc
        .perform(
            jsonPostAs(url(f), "{\"propertyId\":" + extra + "}", f.cookie())
                .header("If-Match", version + 5))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
        .andExpect(jsonPath("$.currentVersion").value(version));
    mockMvc
        .perform(
            deleteAs(url(f) + "/" + candidateId(f, 0), f.cookie()).header("If-Match", version + 5))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    mockMvc
        .perform(
            jsonPostAs(url(f), "{\"propertyId\":" + extra + "}", f.cookie())
                .header("If-Match", version))
        .andExpect(status().isCreated());
  }

  private long candidateId(Fixture f, int index) {
    return jdbcTemplate
        .queryForList(
            "SELECT id FROM visit_candidate_property WHERE coordination_id = ? ORDER BY sort_order",
            Long.class,
            f.coordinationId())
        .get(index);
  }

  @Test
  void 후보를_제거하면_남은_순서가_이어지고_부모_버전이_오른다() throws Exception {
    Fixture f = fixture(3);
    long before = coordinationVersion(f.coordinationId());

    mockMvc
        .perform(deleteAs(url(f) + "/" + candidateId(f, 0), f.cookie()))
        .andExpect(status().isNoContent());

    assertThat(orderedProperties(f.coordinationId()))
        .containsExactly(f.candidates().get(1), f.candidates().get(2));
    List<Integer> orders =
        jdbcTemplate.queryForList(
            "SELECT sort_order FROM visit_candidate_property WHERE coordination_id = ? ORDER BY"
                + " sort_order",
            Integer.class,
            f.coordinationId());
    assertThat(orders).containsExactly(1, 2);
    assertThat(coordinationVersion(f.coordinationId())).isEqualTo(before + 1);
  }

  @Test
  void 시간_수집_중이_아니거나_마지막_후보이면_제거할_수_없다() throws Exception {
    Fixture f = fixture(2);
    long first = candidateId(f, 0);

    setCoordinationStatus(f.coordinationId(), "COORDINATABLE");
    assertError(
        mockMvc.perform(deleteAs(url(f) + "/" + first, f.cookie())), ErrorCode.INVALID_TRANSITION);
    setCoordinationStatus(f.coordinationId(), "TIME_COLLECTING");
    mockMvc.perform(deleteAs(url(f) + "/" + first, f.cookie())).andExpect(status().isNoContent());
    assertError(
        mockMvc.perform(deleteAs(url(f) + "/" + candidateId(f, 0), f.cookie())),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 없는_후보나_다른_조율의_후보를_제거하면_404다() throws Exception {
    Fixture f = fixture(2);
    Fixture other = fixture(2);
    long otherCandidate = candidateId(other, 0);

    assertError(
        mockMvc.perform(deleteAs(url(f) + "/999999", f.cookie())), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(deleteAs(url(f) + "/" + otherCandidate, f.cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 후보_엔드포인트는_401_403_다른_사무소_404를_지킨다() throws Exception {
    Fixture f = fixture(2);
    var foreign = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(f.owner().office(), OfficeMembershipRole.DEPUTY);
    Cookie plain = kakaoSessionFor(saveEmailUser("nomember@example.com", "010-1212-3434"));
    long extra = newProperty(f.cookie());
    String addBody = "{\"propertyId\":" + extra + "}";
    String removeUrl = url(f) + "/" + candidateId(f, 0);

    assertError(mockMvc.perform(jsonPostAs(url(f), addBody, null)), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(deleteAs(removeUrl, null)), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(jsonPostAs(url(f), addBody, plain)), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(deleteAs(removeUrl, plain)), ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(jsonPostAs(url(f), addBody, cookieOf(deputy))), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(deleteAs(removeUrl, cookieOf(deputy))), ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(jsonPostAs(url(f), addBody, cookieOf(foreign))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(deleteAs(removeUrl, cookieOf(foreign))), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 배정된_중개보조원은_본인_조율에_후보를_추가할_수_있다() throws Exception {
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
                today(),
                today().plusDays(5),
                newProperty(ownerCookie))
            .get("coordinationId")
            .longValue();

    mockMvc
        .perform(
            jsonPostAs(
                "/api/visit-coordinations/" + id + "/candidate-properties",
                "{\"propertyId\":" + newProperty(ownerCookie) + "}",
                cookieOf(deputy)))
        .andExpect(status().isCreated());
  }
}
