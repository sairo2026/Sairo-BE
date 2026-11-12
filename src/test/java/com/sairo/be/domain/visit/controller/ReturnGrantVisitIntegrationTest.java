package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.billing.service.ReturnGrantService;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.GrantPrincipal;
import com.sairo.be.global.security.GrantType;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitOutcomeTestBase;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class ReturnGrantVisitIntegrationTest extends VisitOutcomeTestBase {

  @Autowired private ReturnGrantService returnGrantService;

  private record Grant(SelectionFixture visit, long grantId, Cookie cookie) {

    long officeId() {
      return visit.base().owner().office().getId();
    }

    long userId() {
      return visit.base().owner().user().getId();
    }

    long membershipId() {
      return visit.base().owner().membership().getId();
    }
  }

  /** 확정 방문이 있는 사무소를 종료한 상태(대표 소속 해제)로 두고 대표에게 반환권한 세션을 준다. */
  private Grant closedOfficeGrant() throws Exception {
    SelectionFixture s = confirmed();
    long officeId = s.base().owner().office().getId();
    long userId = s.base().owner().user().getId();
    long grantId = returnGrantService.issue(officeId, userId, Instant.now()).getId();
    jdbcTemplate.update(
        "UPDATE office_membership SET status = 'REVOKED', revoked_at = now(), revoked_by_type ="
            + " 'SYSTEM', revocation_reason = '사무소 종료' WHERE office_id = ?",
        officeId);
    return new Grant(s, grantId, grantCookie(userId, grantId, officeId));
  }

  private Cookie grantCookie(long userId, long grantId, long officeId) {
    return sessions.cookie(
        sessions.createGrantSession(
            new GrantPrincipal(userId, grantId, GrantType.RETURN, officeId), Instant.now()));
  }

  private static String base(long grantId) {
    return "/api/return-grants/" + grantId;
  }

  private ResultActions cancelAs(Grant g, long grantId, long coordinationId, Object version)
      throws Exception {
    return postVersioned(
        base(grantId) + "/visit-coordinations/" + coordinationId + "/cancel",
        "{\"reason\":\"종료로 인한 취소\"}",
        g.cookie(),
        version);
  }

  private ResultActions resultAs(
      Cookie cookie, long grantId, long itemId, String body, Object version) throws Exception {
    return mockMvc.perform(
        jsonPatchAs(
            base(grantId) + "/visit-schedule-items/" + itemId + "/result", body, cookie, version));
  }

  private static String completedWithEffective(int minutesAgo, String effective) {
    return "{\"result\":\"COMPLETED\",\"actualVisitedAt\":\""
        + minutesAgo(minutesAgo)
        + "\",\"effectiveAt\":\""
        + effective
        + "\"}";
  }

  @Test
  void 반환권한으로_방문_결과를_사후_기록하면_보존된_소속으로_이력을_남기고_효력_시각을_따로_둔다() throws Exception {
    Grant g = closedOfficeGrant();
    long coordination = g.visit().coordinationId();
    long item = scheduledItem(coordination);
    String effective = minutesAgo(30);
    resultAs(
            g.cookie(),
            g.grantId(),
            item,
            completedWithEffective(40, effective),
            coordinationVersion(coordination))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.itemId").value(item))
        .andExpect(jsonPath("$.coordinationId").value(coordination))
        .andExpect(jsonPath("$.officeId").value(g.officeId()))
        .andExpect(jsonPath("$.result").value("COMPLETED"))
        .andExpect(jsonPath("$.actualVisitedAt").exists())
        .andExpect(jsonPath("$.effectiveAt").exists())
        .andExpect(jsonPath("$.recordedAt").exists())
        .andExpect(jsonPath("$.status").value("VISIT_COMPLETED"))
        .andExpect(jsonPath("$.version").value(coordinationVersion(coordination)));
    Map<String, Object> history =
        jdbcTemplate.queryForMap(
            "SELECT result, recorded_by_membership_id, effective_at FROM"
                + " visit_schedule_item_result_history WHERE schedule_item_id = ? ORDER BY id DESC"
                + " LIMIT 1",
            item);
    assertThat(history.get("result")).isEqualTo("COMPLETED");
    assertThat(history.get("recorded_by_membership_id")).isEqualTo(g.membershipId());
    assertThat(((Timestamp) history.get("effective_at")).toInstant())
        .isEqualTo(java.time.OffsetDateTime.parse(effective).toInstant());
  }

  @Test
  void 효력_시각이_없거나_미래이거나_허용되지_않는_필드가_있으면_400이다() throws Exception {
    Grant g = closedOfficeGrant();
    long coordination = g.visit().coordinationId();
    long item = scheduledItem(coordination);
    long version = coordinationVersion(coordination);
    for (String body :
        new String[] {
          completedBody(10),
          completedWithEffective(10, java.time.OffsetDateTime.now().plusHours(1).toString()),
          "{\"result\":\"SCHEDULED\",\"effectiveAt\":\"" + minutesAgo(1) + "\"}",
          "{\"result\":\"NO_SHOW\",\"effectiveAt\":\"" + minutesAgo(1) + "\",\"memo\":\"x\"}"
        }) {
      assertError(
          resultAs(g.cookie(), g.grantId(), item, body, version), ErrorCode.INVALID_REQUEST);
    }
    assertThat(itemResult(item)).isEqualTo("SCHEDULED");
  }

  @Test
  void 반환권한으로_기존_조율을_취소하면_조율_상태와_부모_버전을_준다() throws Exception {
    Grant g = closedOfficeGrant();
    long coordination = g.visit().coordinationId();
    cancelAs(g, g.grantId(), coordination, coordinationVersion(coordination))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coordinationId").value(coordination))
        .andExpect(jsonPath("$.officeId").value(g.officeId()))
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.version").value(coordinationVersion(coordination)));
    assertThat(coordinationStatus(coordination)).isEqualTo("CANCELLED");
  }

  @Test
  void 반환권한으로_출입승인_철회를_접수하면_기록자는_반환권한_사용자이고_확정_조율은_재조율_필요가_된다() throws Exception {
    Grant g = closedOfficeGrant();
    long coordination = g.visit().coordinationId();
    String body =
        "{\"approvalId\":"
            + g.visit().base().approvalId()
            + ",\"revokedAt\":\""
            + minutesAgo(5)
            + "\",\"reason\":\"승인자의 철회 요청\"}";
    postVersioned(
            base(g.grantId()) + "/visit-coordinations/" + coordination + "/access-revocations",
            body,
            g.cookie(),
            coordinationVersion(coordination))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalId").value(g.visit().base().approvalId()))
        .andExpect(jsonPath("$.officeId").value(g.officeId()))
        .andExpect(jsonPath("$.revokedByUserId").value(g.userId()))
        .andExpect(jsonPath("$.status").value("RESCHEDULE_REQUIRED"));
    assertError(
        postVersioned(
            base(g.grantId()) + "/visit-coordinations/" + coordination + "/access-revocations",
            "{\"approvalId\":1,\"revokedAt\":\"" + minutesAgo(5) + "\"}",
            g.cookie(),
            coordinationVersion(coordination)),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 세션이_없으면_401이고_구성원_세션이나_다른_권한_ID나_만료된_권한은_403_RETURN_GRANT_INVALID이다() throws Exception {
    Grant g = closedOfficeGrant();
    long coordination = g.visit().coordinationId();
    long version = coordinationVersion(coordination);
    String url = base(g.grantId()) + "/visit-coordinations/" + coordination + "/cancel";
    assertError(postVersioned(url, "{}", null, version), ErrorCode.UNAUTHENTICATED);
    SelectionFixture other = confirmed();
    assertError(postVersioned(url, "{}", other.cookie(), version), ErrorCode.RETURN_GRANT_INVALID);
    assertError(
        cancelAs(g, g.grantId() + 100, coordination, version), ErrorCode.RETURN_GRANT_INVALID);
    jdbcTemplate.update(
        "UPDATE return_grant SET granted_at = now() - interval '31 days', expires_at = now() -"
            + " interval '1 day' WHERE id = ?",
        g.grantId());
    assertError(cancelAs(g, g.grantId(), coordination, version), ErrorCode.RETURN_GRANT_INVALID);
    assertThat(coordinationStatus(coordination)).isEqualTo("CONFIRMED");
  }

  @Test
  void 다른_사무소의_조율과_방문은_404이고_버전이_다르면_409이며_인증이_회수된_사무소는_403이다() throws Exception {
    Grant g = closedOfficeGrant();
    SelectionFixture other = confirmed();
    assertError(
        cancelAs(
            g, g.grantId(), other.coordinationId(), coordinationVersion(other.coordinationId())),
        ErrorCode.RESOURCE_NOT_FOUND);
    long otherItem = scheduledItem(other.coordinationId());
    assertError(
        resultAs(
            g.cookie(),
            g.grantId(),
            otherItem,
            completedWithEffective(10, minutesAgo(5)),
            coordinationVersion(other.coordinationId())),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertThat(coordinationStatus(other.coordinationId())).isEqualTo("CONFIRMED");

    long coordination = g.visit().coordinationId();
    assertError(
        cancelAs(g, g.grantId(), coordination, coordinationVersion(coordination) + 5),
        ErrorCode.VERSION_CONFLICT);

    jdbcTemplate.update(
        "INSERT INTO office_registration (applicant_user_id, status, entity_type,"
            + " registration_authority, real_estate_license_number, business_registration_number,"
            + " requested_office_name, requested_representative_name,"
            + " requested_representative_phone, requested_address,"
            + " applicant_registration_relation, resulting_office_id, reviewed_at, revoked_at,"
            + " revocation_reason) VALUES (?, 'REVOKED', 'INDIVIDUAL', '서울', ?, '1234567890',"
            + " '사무소', '홍대표', '010-0000-0000', '서울', '대표', ?, now(), now(), '회수')",
        g.userId(),
        "LIC-REVOKED-" + g.officeId(),
        g.officeId());
    assertError(
        cancelAs(g, g.grantId(), coordination, coordinationVersion(coordination)),
        ErrorCode.FORBIDDEN);
    assertThat(coordinationStatus(coordination)).isEqualTo("CONFIRMED");
  }
}
