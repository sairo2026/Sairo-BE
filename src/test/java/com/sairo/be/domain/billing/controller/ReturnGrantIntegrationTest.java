package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.stub.InMemoryObjectStorage;
import com.sairo.be.global.security.GrantType;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class ReturnGrantIntegrationTest extends GrantTestBase {

  private static final String CURRENT = "/api/return-grants/current";

  @Autowired private InMemoryObjectStorage objectStorage;

  private record Session(Holder holder, long grantId, Cookie cookie) {}

  private Session session() {
    Holder holder = emailHolder();
    long grantId = returnGrant(holder);
    return new Session(
        holder,
        grantId,
        forgedGrantSession(
            GrantType.RETURN, grantId, holder.user().getId(), holder.former().office().getId()));
  }

  @Test
  void 반환권한_상태는_권한_ID와_만료_시각과_회수_여부를_준다() throws Exception {
    Session session = session();
    mockMvc
        .perform(getRequest(CURRENT).apply(session.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grantId").value(session.grantId()))
        .andExpect(jsonPath("$.officeId").value(session.holder().former().office().getId()))
        .andExpect(jsonPath("$.expiresAt").exists())
        .andExpect(jsonPath("$.revoked").value(false));
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT granted_at, expires_at FROM return_grant WHERE id = ?", session.grantId());
    assertThat(((java.sql.Timestamp) row.get("expires_at")).toInstant())
        .isEqualTo(
            ((java.sql.Timestamp) row.get("granted_at")).toInstant().plusSeconds(86_400L * 30));
  }

  @Test
  void 발급한_뒤_만료되거나_회수되면_매_요청마다_권한_행을_다시_검사해_403_RETURN_GRANT_INVALID로_거절한다() throws Exception {
    Session expired = session();
    expireReturnGrant(expired.grantId());
    assertError(
        mockMvc.perform(getRequest(CURRENT).apply(expired.cookie())),
        ErrorCode.RETURN_GRANT_INVALID);
    Session revoked = session();
    jdbcTemplate.update(
        "UPDATE return_grant SET revoked_at = now(), revoked_reason = '회수' WHERE id = ?",
        revoked.grantId());
    assertError(
        mockMvc.perform(getRequest(CURRENT).apply(revoked.cookie())),
        ErrorCode.RETURN_GRANT_INVALID);
  }

  @Test
  void 새_반환권한을_발급하면_이전_활성_권한은_회수되고_대체_권한을_가리킨다() throws Exception {
    Holder holder = emailHolder();
    long first = returnGrant(holder);
    long second = returnGrant(holder);
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT revoked_at, revoked_reason, replaced_by_grant_id FROM return_grant WHERE id = ?",
            first);
    assertThat(row.get("revoked_at")).isNotNull();
    assertThat(row.get("revoked_reason")).isEqualTo("재발급");
    assertThat(row.get("replaced_by_grant_id")).isEqualTo(second);
    Cookie cookie =
        forgedGrantSession(
            GrantType.RETURN, first, holder.user().getId(), holder.former().office().getId());
    assertError(mockMvc.perform(getRequest(CURRENT).apply(cookie)), ErrorCode.RETURN_GRANT_INVALID);
  }

  @Test
  void 로그인하지_않았으면_401이고_일반_구성원_세션과_보관권한_세션은_RETURN_GRANT_INVALID이다() throws Exception {
    Session session = session();
    assertError(mockMvc.perform(getRequest(CURRENT).apply(null)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(getRequest(CURRENT).apply(cookieOf(owner()))),
        ErrorCode.RETURN_GRANT_INVALID);
    Cookie archive =
        forgedGrantSession(
            GrantType.ARCHIVE,
            session.grantId(),
            session.holder().user().getId(),
            session.holder().former().office().getId());
    assertError(
        mockMvc.perform(getRequest(CURRENT).apply(archive)), ErrorCode.RETURN_GRANT_INVALID);
  }

  @Test
  void 내보내기를_만들면_진행_중_상태로_파일_삭제_시각을_24시간_뒤로_기록한다() throws Exception {
    Session session = session();
    var result =
        mockMvc
            .perform(
                postRequest("/api/return-grants/" + session.grantId() + "/export", "")
                    .apply(session.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.downloadUrl").value(nullValue()))
            .andExpect(jsonPath("$.expiresAt").value(nullValue()))
            .andExpect(jsonPath("$.fileExpiresAt").exists())
            .andExpect(jsonPath("$.failureReason").value(nullValue()))
            .andReturn();
    long exportId = AuthFlowSupport.json(result).get("exportId").asLong();
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT scope, status, created_by_membership_id, file_expires_at, created_at FROM export_job"
                + " WHERE id = ?",
            exportId);
    assertThat(row.get("scope")).isEqualTo("FULL");
    assertThat(row.get("status")).isEqualTo("PENDING");
    assertThat(row.get("created_by_membership_id"))
        .isEqualTo(session.holder().former().membership().getId());
    assertThat(((java.sql.Timestamp) row.get("file_expires_at")).toInstant())
        .isEqualTo(((java.sql.Timestamp) row.get("created_at")).toInstant().plusSeconds(86_400));
  }

  @Test
  void 내보내기는_경로의_권한_ID가_세션의_권한과_다르거나_권한이_회수됐으면_403이다() throws Exception {
    Session session = session();
    assertError(
        mockMvc.perform(
            postRequest("/api/return-grants/" + (session.grantId() + 1) + "/export", "")
                .apply(session.cookie())),
        ErrorCode.RETURN_GRANT_INVALID);
    jdbcTemplate.update(
        "UPDATE return_grant SET revoked_at = now(), revoked_reason = '회수' WHERE id = ?",
        session.grantId());
    assertError(
        mockMvc.perform(
            postRequest("/api/return-grants/" + session.grantId() + "/export", "")
                .apply(session.cookie())),
        ErrorCode.RETURN_GRANT_INVALID);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM export_job", Integer.class))
        .isZero();
  }

  @Test
  void 완료된_내보내기는_10분_유효한_다운로드_주소를_주고_진행_중이면_주소가_없다() throws Exception {
    Session session = session();
    long exportId = createExport(session);
    mockMvc
        .perform(getRequest(exportUrl(session, exportId)).apply(session.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.downloadUrl").value(nullValue()));
    objectStorage.put("exports/" + exportId + ".zip", new byte[] {1, 2, 3}, "application/zip");
    jdbcTemplate.update(
        "UPDATE export_job SET status = 'COMPLETED', file_object_key = ? WHERE id = ?",
        "exports/" + exportId + ".zip",
        exportId);
    mockMvc
        .perform(getRequest(exportUrl(session, exportId)).apply(session.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.downloadUrl").isString())
        .andExpect(jsonPath("$.expiresAt").exists())
        .andExpect(jsonPath("$.fileExpiresAt").exists())
        .andExpect(jsonPath("$.failureReason").value(nullValue()));
  }

  @Test
  void 실패한_내보내기는_주소_없이_실패_사유를_준다() throws Exception {
    Session session = session();
    long exportId = createExport(session);
    jdbcTemplate.update(
        "UPDATE export_job SET status = 'FAILED', failure_reason = '파일 생성 실패' WHERE id = ?",
        exportId);
    mockMvc
        .perform(getRequest(exportUrl(session, exportId)).apply(session.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.downloadUrl").value(nullValue()))
        .andExpect(jsonPath("$.failureReason").value("파일 생성 실패"));
  }

  @Test
  void 삭제_시각이_지난_내보내기는_410_EXPORT_EXPIRED이다() throws Exception {
    Session session = session();
    long exportId = createExport(session);
    jdbcTemplate.update(
        "UPDATE export_job SET created_at = now() - interval '25 hours', updated_at = now() - interval"
            + " '25 hours', file_expires_at = now() - interval '1 hour' WHERE id = ?",
        exportId);
    assertError(
        mockMvc.perform(getRequest(exportUrl(session, exportId)).apply(session.cookie())),
        ErrorCode.EXPORT_EXPIRED);
  }

  @Test
  void 없거나_다른_사무소의_내보내기는_404이고_회수된_권한은_403이다() throws Exception {
    Session session = session();
    Session other = session();
    long otherExport = createExport(other);
    assertError(
        mockMvc.perform(getRequest(exportUrl(session, otherExport)).apply(session.cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(getRequest(exportUrl(session, 999_999)).apply(session.cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
    long mine = createExport(session);
    jdbcTemplate.update(
        "UPDATE return_grant SET revoked_at = now(), revoked_reason = '회수' WHERE id = ?",
        session.grantId());
    assertError(
        mockMvc.perform(getRequest(exportUrl(session, mine)).apply(session.cookie())),
        ErrorCode.RETURN_GRANT_INVALID);
  }

  @Test
  void 소속_이력이_없는_사용자의_내보내기는_403_RETURN_GRANT_INVALID이다() throws Exception {
    Holder holder = emailHolder();
    long grantId = returnGrant(holder);
    jdbcTemplate.update("DELETE FROM office_membership WHERE user_id = ?", holder.user().getId());
    Cookie cookie =
        forgedGrantSession(
            GrantType.RETURN, grantId, holder.user().getId(), holder.former().office().getId());
    assertError(
        mockMvc.perform(postRequest("/api/return-grants/" + grantId + "/export", "").apply(cookie)),
        ErrorCode.RETURN_GRANT_INVALID);
  }

  @Test
  void 반환권한_엔드포인트는_일반_구성원과_비로그인을_거절한다() throws Exception {
    List<String> urls = List.of("/api/return-grants/1/exports/1");
    for (String url : urls) {
      assertError(mockMvc.perform(getRequest(url).apply(null)), ErrorCode.UNAUTHENTICATED);
      assertError(
          mockMvc.perform(getRequest(url).apply(cookieOf(owner()))),
          ErrorCode.RETURN_GRANT_INVALID);
    }
    assertError(
        mockMvc.perform(postRequest("/api/return-grants/1/export", "").apply(null)),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(postRequest("/api/return-grants/1/export", "").apply(cookieOf(owner()))),
        ErrorCode.RETURN_GRANT_INVALID);
  }

  private long createExport(Session session) throws Exception {
    var result =
        mockMvc
            .perform(
                postRequest("/api/return-grants/" + session.grantId() + "/export", "")
                    .apply(session.cookie()))
            .andExpect(status().isOk())
            .andReturn();
    return AuthFlowSupport.json(result).get("exportId").asLong();
  }

  private static String exportUrl(Session session, long exportId) {
    return "/api/return-grants/" + session.grantId() + "/exports/" + exportId;
  }
}
