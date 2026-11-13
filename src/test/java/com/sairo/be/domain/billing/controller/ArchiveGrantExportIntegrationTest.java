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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class ArchiveGrantExportIntegrationTest extends GrantTestBase {

  @Autowired private InMemoryObjectStorage objectStorage;

  private record Session(Holder holder, long grantId, Cookie cookie) {}

  private Session session() {
    Holder holder = emailHolder();
    long grantId = archiveGrant(holder.former().office().getId(), holder.user().getId());
    return new Session(
        holder,
        grantId,
        forgedGrantSession(
            GrantType.ARCHIVE, grantId, holder.user().getId(), holder.former().office().getId()));
  }

  private static String createUrl(long grantId) {
    return "/api/archive-grants/" + grantId + "/documents/export";
  }

  private static String statusUrl(long grantId, long exportId) {
    return "/api/archive-grants/" + grantId + "/documents/exports/" + exportId;
  }

  private long createExport(Session session) throws Exception {
    var result =
        mockMvc
            .perform(postRequest(createUrl(session.grantId()), "").apply(session.cookie()))
            .andExpect(status().isCreated())
            .andReturn();
    return AuthFlowSupport.json(result).get("exportId").asLong();
  }

  @Test
  void 내보내기를_만들면_201_진행_중_상태로_ARCHIVE_범위와_보관권한을_기록한다() throws Exception {
    Session session = session();
    var result =
        mockMvc
            .perform(postRequest(createUrl(session.grantId()), "").apply(session.cookie()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.downloadUrl").value(nullValue()))
            .andExpect(jsonPath("$.expiresAt").value(nullValue()))
            .andExpect(jsonPath("$.fileExpiresAt").exists())
            .andReturn();
    long exportId = AuthFlowSupport.json(result).get("exportId").asLong();
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT scope, archive_grant_id, office_id, created_by_membership_id, status,"
                + " file_expires_at, created_at FROM export_job WHERE id = ?",
            exportId);
    assertThat(row.get("scope")).isEqualTo("ARCHIVE");
    assertThat(row.get("archive_grant_id")).isEqualTo(session.grantId());
    assertThat(row.get("office_id")).isEqualTo(session.holder().former().office().getId());
    assertThat(row.get("created_by_membership_id"))
        .isEqualTo(session.holder().former().membership().getId());
    assertThat(
            ((java.sql.Timestamp) row.get("file_expires_at"))
                .toInstant()
                .equals(
                    ((java.sql.Timestamp) row.get("created_at")).toInstant().plusSeconds(86_400)))
        .isTrue();
  }

  @Test
  void 완료된_내보내기는_10분_유효한_다운로드_주소를_주고_진행_중이면_주소가_없다() throws Exception {
    Session session = session();
    long exportId = createExport(session);
    mockMvc
        .perform(getRequest(statusUrl(session.grantId(), exportId)).apply(session.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.downloadUrl").value(nullValue()));
    objectStorage.put("exports/" + exportId + ".zip", new byte[] {1, 2, 3}, "application/zip");
    jdbcTemplate.update(
        "UPDATE export_job SET status = 'COMPLETED', file_object_key = ? WHERE id = ?",
        "exports/" + exportId + ".zip",
        exportId);
    mockMvc
        .perform(getRequest(statusUrl(session.grantId(), exportId)).apply(session.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.downloadUrl").isString())
        .andExpect(jsonPath("$.expiresAt").exists())
        .andExpect(jsonPath("$.failureReason").value(nullValue()));
  }

  @Test
  void 삭제_시각이_지난_내보내기는_410_EXPORT_EXPIRED이다() throws Exception {
    Session session = session();
    long exportId = createExport(session);
    jdbcTemplate.update(
        "UPDATE export_job SET created_at = now() - interval '25 hours', updated_at = now() -"
            + " interval '25 hours', file_expires_at = now() - interval '1 hour' WHERE id = ?",
        exportId);
    assertError(
        mockMvc.perform(getRequest(statusUrl(session.grantId(), exportId)).apply(session.cookie())),
        ErrorCode.EXPORT_EXPIRED);
  }

  @Test
  void 세션의_보관권한과_다른_경로와_다른_보관권한의_작업은_404이다() throws Exception {
    Session session = session();
    Session other = session();
    assertError(
        mockMvc.perform(postRequest(createUrl(other.grantId()), "").apply(session.cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
    long otherExport = createExport(other);
    assertError(
        mockMvc.perform(
            getRequest(statusUrl(session.grantId(), otherExport)).apply(session.cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(getRequest(statusUrl(session.grantId(), 999_999)).apply(session.cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
    long sameOfficeOther =
        jdbcTemplate.queryForObject(
            "INSERT INTO export_job (office_id, created_by_membership_id, scope, status,"
                + " file_expires_at, created_at, updated_at) VALUES (?, ?, 'FULL', 'PENDING', now()"
                + " + interval '24 hours', now(), now()) RETURNING id",
            Long.class,
            session.holder().former().office().getId(),
            session.holder().former().membership().getId());
    assertError(
        mockMvc.perform(
            getRequest(statusUrl(session.grantId(), sameOfficeOther)).apply(session.cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 만료되거나_종료된_보관권한은_403_ARCHIVE_GRANT_INVALID이다() throws Exception {
    Session session = session();
    long exportId = createExport(session);
    jdbcTemplate.update(
        "UPDATE archive_grant SET terminated_at = now(), terminated_reason = '종료' WHERE id = ?",
        session.grantId());
    assertError(
        mockMvc.perform(postRequest(createUrl(session.grantId()), "").apply(session.cookie())),
        ErrorCode.ARCHIVE_GRANT_INVALID);
    assertError(
        mockMvc.perform(getRequest(statusUrl(session.grantId(), exportId)).apply(session.cookie())),
        ErrorCode.ARCHIVE_GRANT_INVALID);
  }

  @Test
  void 소속_이력이_없는_사용자의_내보내기는_403_ARCHIVE_GRANT_INVALID이다() throws Exception {
    Holder holder = emailHolder();
    long officeId = holder.former().office().getId();
    long grantId = archiveGrant(officeId, holder.user().getId());
    jdbcTemplate.update("DELETE FROM office_membership WHERE user_id = ?", holder.user().getId());
    Cookie cookie = forgedGrantSession(GrantType.ARCHIVE, grantId, holder.user().getId(), officeId);
    assertError(
        mockMvc.perform(postRequest(createUrl(grantId), "").apply(cookie)),
        ErrorCode.ARCHIVE_GRANT_INVALID);
  }

  @Test
  void 보관권한_엔드포인트는_비로그인_일반_구성원_반환권한_세션을_거절한다() throws Exception {
    Session session = session();
    assertError(
        mockMvc.perform(postRequest(createUrl(1), "").apply(null)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(getRequest(statusUrl(1, 1)).apply(null)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(postRequest(createUrl(1), "").apply(cookieOf(owner()))),
        ErrorCode.ARCHIVE_GRANT_INVALID);
    assertError(
        mockMvc.perform(getRequest(statusUrl(1, 1)).apply(cookieOf(owner()))),
        ErrorCode.ARCHIVE_GRANT_INVALID);
    Cookie returnSession =
        forgedGrantSession(
            GrantType.RETURN,
            session.grantId(),
            session.holder().user().getId(),
            session.holder().former().office().getId());
    assertError(
        mockMvc.perform(postRequest(createUrl(session.grantId()), "").apply(returnSession)),
        ErrorCode.ARCHIVE_GRANT_INVALID);
  }

  @Test
  void 반환권한은_보관_내보내기를_조회할_수_없다() throws Exception {
    Session archive = session();
    long exportId = createExport(archive);
    long returnGrantId = returnGrant(archive.holder());
    Cookie returnSession =
        forgedGrantSession(
            GrantType.RETURN,
            returnGrantId,
            archive.holder().user().getId(),
            archive.holder().former().office().getId());
    assertError(
        mockMvc.perform(
            getRequest("/api/return-grants/" + returnGrantId + "/exports/" + exportId)
                .apply(returnSession)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }
}
