package com.sairo.be.domain.migration.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.support.ExportIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ExportIntegrationTest extends ExportIntegrationTestBase {

  @Test
  void 대표가_표_내보내기를_요청하면_PENDING으로_접수하고_24시간_뒤_만료로_정한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);

    postExport(cookieOf(owner), tablesBody("CUSTOMER", "PROPERTY"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.exportId").isNumber())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.downloadUrl").doesNotExist())
        .andExpect(jsonPath("$.expiresAt").doesNotExist())
        .andExpect(jsonPath("$.fileExpiresAt").exists());

    var row =
        jdbcTemplate.queryForMap(
            "SELECT scope, table_codes::text AS codes, created_by_membership_id,"
                + " EXTRACT(EPOCH FROM (file_expires_at - created_at)) AS retention FROM export_job");
    assertThat(row.get("scope")).isEqualTo("TABLES");
    assertThat((String) row.get("codes")).contains("CUSTOMER").contains("PROPERTY");
    assertThat(row.get("created_by_membership_id")).isEqualTo(owner.membership().getId());
    assertThat(((Number) row.get("retention")).longValue()).isEqualTo(86_400L);
  }

  @Test
  void 생성된_파일은_조회하면_10분_유효_다운로드_URL로_받는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long exportId = createExport(cookie, tablesBody("CUSTOMER"));

    getExport(cookie, exportId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exportId").value(exportId))
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.downloadUrl").value(containsString("ttl=600")))
        .andExpect(jsonPath("$.expiresAt").exists())
        .andExpect(jsonPath("$.fileExpiresAt").exists())
        .andExpect(jsonPath("$.failureReason").doesNotExist());
  }

  @Test
  void 전체_내보내기는_대표가_최근_재인증을_했을_때만_접수한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);

    assertError(postExport(cookie, "{\"scope\":\"FULL\"}"), ErrorCode.STEP_UP_REQUIRED);
    setSessionAttribute(cookie, StepUpState.STEP_UP_UNTIL_ATTRIBUTE, Instant.now().minusSeconds(5));
    assertError(postExport(cookie, "{\"scope\":\"FULL\"}"), ErrorCode.STEP_UP_REQUIRED);
    assertThat(count("export_job")).isZero();

    grantStepUp(cookie);
    postExport(cookie, "{\"scope\":\"FULL\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT table_codes IS NULL FROM export_job", Boolean.class))
        .isTrue();
  }

  @Test
  void 소속공인중개사는_전체_내보내기를_할_수_없다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie agent = cookieOf(agentOf(owner));
    grantStepUp(agent);

    assertError(postExport(agent, "{\"scope\":\"FULL\"}"), ErrorCode.FORBIDDEN);
    assertThat(count("export_job")).isZero();
  }

  @Test
  void 소속공인중개사는_사무소가_허용한_표만_내보낼_수_있다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie agent = cookieOf(agentOf(owner));

    assertError(postExport(agent, tablesBody("CUSTOMER")), ErrorCode.FORBIDDEN);
    allow(owner.office().getId(), "CUSTOMER", "TASK");
    postExport(agent, tablesBody("CUSTOMER", "TASK")).andExpect(status().isCreated());
    assertError(postExport(agent, tablesBody("CUSTOMER", "CONTRACT")), ErrorCode.FORBIDDEN);
    assertThat(count("export_job")).isEqualTo(1);
  }

  @Test
  void 내보내기_요청의_필수_누락과_형식_오류는_400이다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));

    assertFieldError(postExport(cookie, "{}"), "scope", "REQUIRED");
    assertFieldError(postExport(cookie, "{\"scope\":\"ALL\"}"), "scope", "NOT_ALLOWED");
    assertFieldError(postExport(cookie, "{\"scope\":\"TABLES\"}"), "tables", "REQUIRED");
    assertFieldError(postExport(cookie, tablesBody()), "tables", "REQUIRED");
    assertFieldError(postExport(cookie, tablesBody("USERS")), "tables[]", "NOT_ALLOWED");
    assertFieldError(
        postExport(cookie, "{\"scope\":\"TABLES\",\"tables\":\"CUSTOMER\"}"),
        "tables",
        "TYPE_MISMATCH");
    assertFieldError(
        postExport(cookie, "{\"scope\":\"TABLES\",\"tables\":[1]}"), "tables[]", "TYPE_MISMATCH");
    assertFieldError(
        postExport(cookie, "{\"scope\":\"FULL\",\"tables\":[\"CUSTOMER\"]}"),
        "tables",
        "NOT_ALLOWED");
    assertFieldError(
        postExport(cookie, "{\"scope\":\"FULL\",\"extra\":1}"), "extra", "NOT_ALLOWED");
    assertThat(count("export_job")).isZero();
  }

  @Test
  void 내보내기_생성은_로그인하지_않으면_401이고_중개보조원은_403이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);

    assertError(postExport(null, tablesBody("CUSTOMER")), ErrorCode.UNAUTHENTICATED);
    assertError(
        postExport(
            cookieOf(staffIn(owner.office(), OfficeMembershipRole.DEPUTY)), tablesBody("CUSTOMER")),
        ErrorCode.FORBIDDEN);
  }

  @Test
  void 처리_중과_실패한_내보내기는_URL_없이_상태와_사유를_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long pending = createExport(cookie, tablesBody("TASK"));
    jdbcTemplate.update(
        "UPDATE export_job SET status = 'PENDING', file_object_key = NULL WHERE id = ?", pending);
    long failed = createExport(cookie, tablesBody("TASK"));
    jdbcTemplate.update(
        "UPDATE export_job SET status = 'FAILED', file_object_key = NULL, failure_reason = '실패'"
            + " WHERE id = ?",
        failed);

    getExport(cookie, pending)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.downloadUrl").doesNotExist())
        .andExpect(jsonPath("$.expiresAt").doesNotExist());
    getExport(cookie, failed)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.downloadUrl").doesNotExist())
        .andExpect(jsonPath("$.failureReason").value("실패"));
  }

  @Test
  void 생성_24시간이_지나_삭제된_파일은_410_EXPORT_EXPIRED이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long exportId = createExport(cookie, tablesBody("TASK"));
    expireExport(exportId);

    assertError(getExport(cookie, exportId), ErrorCode.EXPORT_EXPIRED);
  }

  @Test
  void 파일이_저장소에서_사라졌으면_410_EXPORT_EXPIRED이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long exportId = createExport(cookie, tablesBody("TASK"));
    objectStorage.delete(exportKey(exportId));

    assertError(getExport(cookie, exportId), ErrorCode.EXPORT_EXPIRED);
  }

  @Test
  void 링크만_만료됐고_파일이_남아_있으면_조회할_때마다_새_URL을_받는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long exportId = createExport(cookie, tablesBody("TASK"));

    JsonNode first = body(getExport(cookie, exportId).andExpect(status().isOk()));
    JsonNode second = body(getExport(cookie, exportId).andExpect(status().isOk()));

    assertThat(first.get("downloadUrl").asString()).isNotBlank();
    assertThat(second.get("downloadUrl").asString()).isNotBlank();
    assertThat(second.get("expiresAt").asString()).isNotBlank();
  }

  @Test
  void 조회는_다른_사무소와_없는_ID와_잘못된_ID와_권한을_거른다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    long exportId = createExport(cookieOf(owner), tablesBody("TASK"));

    assertError(getExport(cookieOf(other), exportId), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(getExport(cookieOf(owner), 999_999), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(getExport(cookieOf(owner), "abc"), ErrorCode.INVALID_REQUEST);
    assertError(getExport(null, exportId), ErrorCode.UNAUTHENTICATED);
    assertError(
        getExport(cookieOf(staffIn(owner.office(), OfficeMembershipRole.DEPUTY)), exportId),
        ErrorCode.FORBIDDEN);
  }

  @Test
  void 소속공인중개사는_타인이_만든_내보내기와_전체_내보내기를_볼_수_없다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var agent = agentOf(owner);
    Cookie ownerCookie = cookieOf(owner);
    allow(owner.office().getId(), "TASK");
    long byOwner = createExport(ownerCookie, tablesBody("TASK"));
    long byAgent = createExport(cookieOf(agent), tablesBody("TASK"));
    grantStepUp(ownerCookie);
    long full = createExport(ownerCookie, "{\"scope\":\"FULL\"}");

    getExport(cookieOf(agent), byAgent).andExpect(status().isOk());
    assertError(getExport(cookieOf(agent), byOwner), ErrorCode.FORBIDDEN);
    assertError(getExport(cookieOf(agent), full), ErrorCode.FORBIDDEN);
    getExport(ownerCookie, byAgent).andExpect(status().isOk());
  }

  @Test
  void 허용_표가_철회되면_이미_만든_내보내기도_다운로드_때_다시_거절한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var agent = agentOf(owner);
    allow(owner.office().getId(), "TASK");
    long exportId = createExport(cookieOf(agent), tablesBody("TASK"));
    getExport(cookieOf(agent), exportId).andExpect(status().isOk());

    allow(owner.office().getId());

    assertError(getExport(cookieOf(agent), exportId), ErrorCode.FORBIDDEN);
    getExport(cookieOf(owner), exportId).andExpect(status().isOk());
  }

  @Test
  void 목록은_대표는_사무소_전체를_소속공인중개사는_본인_것만_보여준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var agent = agentOf(owner);
    var other = newStaff(OfficeMembershipRole.OWNER);
    allow(owner.office().getId(), "TASK");
    createExport(cookieOf(owner), tablesBody("CUSTOMER"));
    long agentExport = createExport(cookieOf(agent), tablesBody("TASK"));
    createExport(cookieOf(other), tablesBody("TASK"));

    mockMvc
        .perform(authedGet("/api/exports", cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.items[0].exportId").value(agentExport))
        .andExpect(jsonPath("$.items[0].scope").value("TABLES"))
        .andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
        .andExpect(jsonPath("$.items[0].createdAt").exists())
        .andExpect(jsonPath("$.items[0].fileExpiresAt").exists());
    mockMvc
        .perform(authedGet("/api/exports", cookieOf(agent)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].exportId").value(agentExport));
  }

  @Test
  void 목록은_파일이_삭제된_건을_빼고_status와_페이지로_거르며_잘못된_값은_400이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long expired = createExport(cookie, tablesBody("TASK"));
    expireExport(expired);
    long failed = createExport(cookie, tablesBody("TASK"));
    jdbcTemplate.update(
        "UPDATE export_job SET status = 'FAILED', file_object_key = NULL, failure_reason = '실패'"
            + " WHERE id = ?",
        failed);
    long done = createExport(cookie, tablesBody("TASK"));

    mockMvc
        .perform(authedGet("/api/exports", cookie))
        .andExpect(jsonPath("$.totalElements").value(2));
    mockMvc
        .perform(authedGet("/api/exports?status=FAILED", cookie))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].exportId").value(failed));
    mockMvc
        .perform(authedGet("/api/exports?status=COMPLETED&size=50", cookie))
        .andExpect(jsonPath("$.size").value(50))
        .andExpect(jsonPath("$.items[0].exportId").value(done));
    mockMvc
        .perform(authedGet("/api/exports?page=3", cookie))
        .andExpect(jsonPath("$.items.length()").value(0));
    assertFieldError(
        mockMvc.perform(authedGet("/api/exports?status=NOPE", cookie)), "status", "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(authedGet("/api/exports?size=7", cookie)), "size", "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(authedGet("/api/exports?page=-1", cookie)), "page", "OUT_OF_RANGE");
  }

  @Test
  void 목록은_로그인하지_않으면_401이고_중개보조원은_403이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);

    assertError(mockMvc.perform(authedGet("/api/exports", null)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(
            authedGet(
                "/api/exports", cookieOf(staffIn(owner.office(), OfficeMembershipRole.DEPUTY)))),
        ErrorCode.FORBIDDEN);
  }

  @Test
  void 허용_표_목록은_대표에게_전체와_현재_허용_여부를_소속공인중개사에게_허용된_표만_준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var agent = agentOf(owner);
    allow(owner.office().getId(), "CUSTOMER", "VISIT");

    mockMvc
        .perform(authedGet("/api/exports/allowed-tables", cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tables.length()").value(7))
        .andExpect(jsonPath("$.tables[0].code").value("PROPERTY"))
        .andExpect(jsonPath("$.tables[0].label").value("매물"))
        .andExpect(jsonPath("$.tables[0].allowed").value(false))
        .andExpect(jsonPath("$.tables[1].code").value("CUSTOMER"))
        .andExpect(jsonPath("$.tables[1].allowed").value(true))
        .andExpect(jsonPath("$.tables[4].label").value("잔금·지급"))
        .andExpect(jsonPath("$.tables[6].code").value("VISIT"))
        .andExpect(jsonPath("$.tables[6].allowed").value(true));
    mockMvc
        .perform(authedGet("/api/exports/allowed-tables", cookieOf(agent)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tables.length()").value(2))
        .andExpect(jsonPath("$.tables[0].code").value("CUSTOMER"))
        .andExpect(jsonPath("$.tables[0].allowed").value(true))
        .andExpect(jsonPath("$.tables[1].code").value("VISIT"));
  }

  @Test
  void 허용된_표가_없으면_소속공인중개사의_허용_표_목록은_빈_배열이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);

    mockMvc
        .perform(authedGet("/api/exports/allowed-tables", cookieOf(agentOf(owner))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tables.length()").value(0));
  }

  @Test
  void 허용_표_조회는_로그인하지_않으면_401이고_중개보조원은_403이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);

    assertError(
        mockMvc.perform(authedGet("/api/exports/allowed-tables", null)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(
            authedGet(
                "/api/exports/allowed-tables",
                cookieOf(staffIn(owner.office(), OfficeMembershipRole.DEPUTY)))),
        ErrorCode.FORBIDDEN);
  }

  private ResultActions putPolicy(Cookie cookie, String body) throws Exception {
    return mockMvc.perform(jsonPutAs("/api/offices/current/export-policy", body, cookie, null));
  }

  @Test
  void 대표가_허용_표_목록을_보내면_전체를_대체하고_결과를_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    allow(owner.office().getId(), "TASK");

    putPolicy(cookie, "{\"allowedTables\":[\"CUSTOMER\",\"PROPERTY\",\"CUSTOMER\"]}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tables.length()").value(7))
        .andExpect(jsonPath("$.tables[0].code").value("PROPERTY"))
        .andExpect(jsonPath("$.tables[0].allowed").value(true))
        .andExpect(jsonPath("$.tables[1].code").value("CUSTOMER"))
        .andExpect(jsonPath("$.tables[1].allowed").value(true))
        .andExpect(jsonPath("$.tables[5].code").value("TASK"))
        .andExpect(jsonPath("$.tables[5].allowed").value(false));
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT table_code FROM office_export_table_policy WHERE office_id = ? ORDER BY"
                    + " table_code",
                String.class,
                owner.office().getId()))
        .containsExactly("CUSTOMER", "PROPERTY");

    putPolicy(cookie, "{\"allowedTables\":[]}").andExpect(status().isOk());
    assertThat(count("office_export_table_policy")).isZero();
  }

  @Test
  void 허용_표_설정은_다른_사무소의_정책을_바꾸지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    allow(other.office().getId(), "TASK");

    putPolicy(cookieOf(owner), "{\"allowedTables\":[\"CUSTOMER\"]}").andExpect(status().isOk());

    assertThat(
            jdbcTemplate.queryForList(
                "SELECT table_code FROM office_export_table_policy WHERE office_id = ?",
                String.class,
                other.office().getId()))
        .containsExactly("TASK");
  }

  @Test
  void 허용_표_설정의_필수_누락과_알_수_없는_표_코드는_400이다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));

    assertFieldError(putPolicy(cookie, "{}"), "allowedTables", "REQUIRED");
    assertFieldError(
        putPolicy(cookie, "{\"allowedTables\":[\"USERS\"]}"), "allowedTables[]", "NOT_ALLOWED");
    assertFieldError(
        putPolicy(cookie, "{\"allowedTables\":\"CUSTOMER\"}"), "allowedTables", "TYPE_MISMATCH");
    assertFieldError(putPolicy(cookie, "{\"allowedTables\":[],\"x\":1}"), "x", "NOT_ALLOWED");
  }

  @Test
  void 허용_표_설정은_대표만_할_수_있고_로그인이_필요하다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);

    assertError(putPolicy(cookieOf(agentOf(owner)), "{\"allowedTables\":[]}"), ErrorCode.FORBIDDEN);
    assertError(putPolicy(null, "{\"allowedTables\":[]}"), ErrorCode.UNAUTHENTICATED);
  }
}
