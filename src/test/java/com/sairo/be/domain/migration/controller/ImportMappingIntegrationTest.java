package com.sairo.be.domain.migration.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static com.sairo.be.support.SpreadsheetFixtures.csv;
import static com.sairo.be.support.SpreadsheetFixtures.row;
import static com.sairo.be.support.SpreadsheetFixtures.sheets;
import static com.sairo.be.support.SpreadsheetFixtures.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.ImportIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class ImportMappingIntegrationTest extends ImportIntegrationTestBase {

  private static String mapping(String sourceColumn, String targetField) {
    return "{\"sourceSystem\":\"양셀\",\"mappings\":[{\"sourceColumn\":\""
        + sourceColumn
        + "\",\"targetField\":\""
        + targetField
        + "\"}]}";
  }

  @Test
  void 매핑_제안은_원본_열과_샘플_값_제안_신뢰도_대상_항목_목록을_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId =
        uploadCustomers(
            cookie,
            "1,홍길동,010-1111-2222,a@b.co,메모1",
            "2,김철수,010-3333-4444,c@d.co,",
            "3,이영희,,,메모3",
            "4,박민수,010-5555-6666,e@f.co,메모4");

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/mapping-suggestion", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceColumns.length()").value(5))
        .andExpect(jsonPath("$.sourceColumns[1].name").value("성명"))
        .andExpect(jsonPath("$.sourceColumns[1].sampleValues.length()").value(3))
        .andExpect(jsonPath("$.sourceColumns[1].sampleValues[0]").value("홍길동"))
        .andExpect(jsonPath("$.sourceColumns[1].mappedTargetField").value("customerName"))
        .andExpect(jsonPath("$.sourceColumns[4].sampleValues.length()").value(3))
        .andExpect(jsonPath("$.suggestedMappings.length()").value(5))
        .andExpect(jsonPath("$.suggestedMappings[0].sourceColumn").value("고객번호"))
        .andExpect(jsonPath("$.suggestedMappings[0].targetField").value("customerSourceId"))
        .andExpect(jsonPath("$.suggestedMappings[0].confidence").value(0.95))
        .andExpect(jsonPath("$.availableTargetFields.length()").value(7))
        .andExpect(jsonPath("$.availableTargetFields[0].field").value("customerSourceId"))
        .andExpect(jsonPath("$.availableTargetFields[0].label").value("원본 고객 ID"))
        .andExpect(jsonPath("$.availableTargetFields[0].type").value("string"))
        .andExpect(jsonPath("$.availableTargetFields[0].required").value(true))
        .andExpect(jsonPath("$.availableTargetFields[2].field").value("customerIsCorporation"))
        .andExpect(jsonPath("$.availableTargetFields[2].required").value(false));
  }

  @Test
  void 제안되지_않은_열은_mappedTargetField가_null이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadBatch(cookie, "a.csv", csv("고객번호,성명,알수없는열", "1,홍길동,x"), "CUSTOMER");

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/mapping-suggestion", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceColumns[2].name").value("알수없는열"))
        .andExpect(jsonPath("$.sourceColumns[2].mappedTargetField").doesNotExist())
        .andExpect(jsonPath("$.suggestedMappings.length()").value(2));
  }

  @Test
  void 매물_유형의_대상_항목은_15개다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadProperties(cookie, "1,빌딩,서울,101호,30평,5만원,2026-03-05,공실");

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/mapping-suggestion", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.availableTargetFields.length()").value(15))
        .andExpect(jsonPath("$.availableTargetFields[0].field").value("propertySourceId"))
        .andExpect(jsonPath("$.availableTargetFields[4].field").value("exclusiveAreaSqm"))
        .andExpect(jsonPath("$.availableTargetFields[4].type").value("number"))
        .andExpect(jsonPath("$.availableTargetFields[8].field").value("moveInDate"))
        .andExpect(jsonPath("$.availableTargetFields[8].type").value("date"));
  }

  @Test
  void 이미_있는_계약_유형_실행_단위는_허용_목록이_없어_대상_항목이_빈_배열이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadBatch(cookie, "c.csv", csv("계약번호,당사자", "1,홍길동"), "CUSTOMER");
    jdbcTemplate.update("UPDATE import_batch SET target_type = 'CONTRACT' WHERE id = ?", batchId);

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/mapping-suggestion", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.availableTargetFields.length()").value(0))
        .andExpect(jsonPath("$.suggestedMappings.length()").value(0))
        .andExpect(jsonPath("$.sourceColumns.length()").value(2));
    assertFieldError(
        mockMvc.perform(
            jsonPutAs(
                "/api/import-batches/" + batchId + "/mapping",
                mapping("계약번호", "customerSourceId"),
                cookie,
                batchVersion(batchId))),
        "mappings[].targetField",
        "NOT_ALLOWED");
  }

  @Test
  void 매핑을_확정하면_새_버전과_열_대응을_저장하고_MAPPING_상태가_된다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "1,홍길동,010-1111-2222,a@b.co,메모");

    mockMvc
        .perform(
            jsonPutAs(
                "/api/import-batches/" + batchId + "/mapping",
                CUSTOMER_MAPPING,
                cookie,
                batchVersion(batchId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.batchId").value(batchId))
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.status").value("MAPPING"))
        .andExpect(jsonPath("$.rowCount").value(1))
        .andExpect(jsonPath("$.executedAt").doesNotExist())
        .andExpect(jsonPath("$.cancellableUntil").doesNotExist())
        .andExpect(jsonPath("$.cancelledAt").doesNotExist())
        .andExpect(jsonPath("$.version").value(2));

    Map<String, Object> version = jdbcTemplate.queryForMap("SELECT * FROM import_mapping_version");
    assertThat(version)
        .containsEntry("version_no", 1)
        .containsEntry("source_system", "양셀")
        .containsEntry("target_type", "CUSTOMER")
        .containsEntry("confirmed_by_type", "MEMBERSHIP")
        .containsEntry("confirmed_by_membership_id", owner.membership().getId());
    assertThat(count("import_mapping_item")).isEqualTo(5);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT confidence FROM import_mapping_item WHERE target_field = 'customerName'",
                java.math.BigDecimal.class))
        .isEqualByComparingTo("0.95");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT source_system FROM import_batch WHERE id = ?", String.class, batchId))
        .isEqualTo("양셀");
  }

  @Test
  void 다시_확정하면_버전이_올라가고_이전_버전은_바뀌지_않으며_제안_값은_확정_매핑을_보여준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "1,홍길동,010-1111-2222,a@b.co,메모");
    confirmMapping(cookie, batchId, CUSTOMER_MAPPING);
    List<Map<String, Object>> before =
        jdbcTemplate.queryForList(
            "SELECT * FROM import_mapping_item WHERE mapping_version_id ="
                + " (SELECT id FROM import_mapping_version WHERE version_no = 1) ORDER BY id");

    confirmMapping(
        cookie,
        batchId,
        "{\"sourceSystem\":\"다른시스템\",\"mappings\":["
            + "{\"sourceColumn\":\"고객번호\",\"targetField\":\"customerSourceId\"},"
            + "{\"sourceColumn\":\"성명\",\"targetField\":\"customerName\"},"
            + "{\"sourceColumn\":\"메모\",\"targetField\":\"customerMemo\"}]}");

    assertThat(count("import_mapping_version")).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT version_no FROM import_mapping_version ORDER BY version_no", Integer.class))
        .containsExactly(1, 2);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT * FROM import_mapping_item WHERE mapping_version_id ="
                    + " (SELECT id FROM import_mapping_version WHERE version_no = 1) ORDER BY id"))
        .isEqualTo(before);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT source_system FROM import_mapping_version WHERE version_no = 2",
                String.class))
        .isEqualTo("다른시스템");
    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/mapping-suggestion", cookie))
        .andExpect(jsonPath("$.sourceColumns[2].mappedTargetField").doesNotExist())
        .andExpect(jsonPath("$.sourceColumns[1].mappedTargetField").value("customerName"));
  }

  @Test
  void 허용_목록에_없는_대상_항목은_400이고_fieldErrors에_mappings_targetField를_담는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "1,홍길동,,,");
    String url = "/api/import-batches/" + batchId + "/mapping";

    assertFieldError(
        mockMvc.perform(
            jsonPutAs(url, mapping("성명", "propertyName"), cookie, batchVersion(batchId))),
        "mappings[].targetField",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPutAs(url, mapping("성명", "unknownField"), cookie, batchVersion(batchId))),
        "mappings[].targetField",
        "NOT_ALLOWED");
    assertThat(count("import_mapping_version")).isZero();
  }

  @Test
  void 원본_ID와_이름_항목을_연결하지_않으면_400이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "1,홍길동,,,");

    assertFieldError(
        mockMvc.perform(
            jsonPutAs(
                "/api/import-batches/" + batchId + "/mapping",
                mapping("성명", "customerName"),
                cookie,
                batchVersion(batchId))),
        "mappings",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPutAs(
                "/api/import-batches/" + batchId + "/mapping",
                mapping("고객번호", "customerSourceId"),
                cookie,
                batchVersion(batchId))),
        "mappings",
        "REQUIRED");
  }

  @Test
  void 없는_열이나_중복_연결과_잘못된_본문은_400이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "1,홍길동,,,");
    String url = "/api/import-batches/" + batchId + "/mapping";
    long version = batchVersion(batchId);

    assertFieldError(
        mockMvc.perform(jsonPutAs(url, mapping("없는열", "customerName"), cookie, version)),
        "mappings[].sourceColumn",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPutAs(
                url,
                "{\"sourceSystem\":\"a\",\"mappings\":["
                    + "{\"sourceColumn\":\"성명\",\"targetField\":\"customerName\"},"
                    + "{\"sourceColumn\":\"성명\",\"targetField\":\"customerSourceId\"}]}",
                cookie,
                version)),
        "mappings[].sourceColumn",
        "INVALID_VALUE");
    assertFieldError(
        mockMvc.perform(
            jsonPutAs(
                url,
                "{\"sourceSystem\":\"a\",\"mappings\":["
                    + "{\"sourceColumn\":\"성명\",\"targetField\":\"customerName\"},"
                    + "{\"sourceColumn\":\"고객번호\",\"targetField\":\"customerName\"}]}",
                cookie,
                version)),
        "mappings[].targetField",
        "INVALID_VALUE");
    assertFieldError(
        mockMvc.perform(jsonPutAs(url, "{\"mappings\":[]}", cookie, version)),
        "sourceSystem",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPutAs(url, "{\"sourceSystem\":\"a\",\"mappings\":[]}", cookie, version)),
        "mappings",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPutAs(
                url, "{\"sourceSystem\":\"a\",\"mappings\":[],\"extra\":1}", cookie, version)),
        "extra",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(jsonPutAs(url, mapping("성명", "customerName"), cookie, null)),
        "If-Match",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPutAs(url, mapping("성명", "customerName"), cookie, "abc")),
        "If-Match",
        "INVALID_FORMAT");
  }

  @Test
  void 매핑의_If_Match가_현재_버전과_다르면_409_VERSION_CONFLICT다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "1,홍길동,,,");

    mockMvc
        .perform(
            jsonPutAs(
                "/api/import-batches/" + batchId + "/mapping",
                CUSTOMER_MAPPING,
                cookie,
                batchVersion(batchId) + 5))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
        .andExpect(jsonPath("$.currentVersion").value(batchVersion(batchId)));
    assertThat(count("import_mapping_version")).isZero();
  }

  @Test
  void 유료_이전_지원에_연결된_실행_단위는_직접_매핑할_수_없고_취소_거절되면_다시_가능하다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "1,홍길동,,,");
    long requestId = insertSupportRequest(owner.office().getId(), batchId, "REQUESTED");
    String url = "/api/import-batches/" + batchId + "/mapping";

    assertError(
        mockMvc.perform(jsonPutAs(url, CUSTOMER_MAPPING, cookie, batchVersion(batchId))),
        ErrorCode.IMPORT_MANAGED_BY_SUPPORT);
    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/mapping-suggestion", cookie))
        .andExpect(status().isOk());

    jdbcTemplate.update(
        "UPDATE import_support_request SET status = 'REJECTED', rejected_at = now(),"
            + " reject_reason = '범위 초과', rejected_by_operator_id = ? WHERE id = ?",
        insertOperator(),
        requestId);
    mockMvc
        .perform(jsonPutAs(url, CUSTOMER_MAPPING, cookie, batchVersion(batchId)))
        .andExpect(status().isOk());
  }

  @Test
  void 실행_이후_상태에서는_매핑을_확정할_수_없다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "1,홍길동,,,");
    forceStatus(batchId, "COMPLETED");

    assertError(
        mockMvc.perform(
            jsonPutAs(
                "/api/import-batches/" + batchId + "/mapping",
                CUSTOMER_MAPPING,
                cookie,
                batchVersion(batchId))),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 다른_사무소의_실행_단위는_404이고_대표가_아니면_403이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    var agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    long batchId = uploadCustomers(cookieOf(owner), "1,홍길동,,,");
    String suggestion = "/api/import-batches/" + batchId + "/mapping-suggestion";
    String mappingUrl = "/api/import-batches/" + batchId + "/mapping";

    assertError(
        mockMvc.perform(authedGet(suggestion, cookieOf(other))), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPutAs(mappingUrl, CUSTOMER_MAPPING, cookieOf(other), 1)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(authedGet(suggestion, cookieOf(agent))), ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(jsonPutAs(mappingUrl, CUSTOMER_MAPPING, cookieOf(agent), 1)),
        ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(authedGet(suggestion, null)), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 시트를_바꾸면_UPLOADED로_돌아가고_제안과_매핑은_새_시트_기준이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    byte[] content =
        xlsx(
            sheets(
                "고객",
                List.of(row("고객번호", "성명"), row(1, "홍길동")),
                "매물",
                List.of(row("매물번호", "매물명", "주소"), row(1, "빌딩", "서울"))));
    long batchId = uploadBatch(cookie, "a.xlsx", content, "CUSTOMER");
    confirmMapping(
        cookie,
        batchId,
        "{\"sourceSystem\":\"a\",\"mappings\":["
            + "{\"sourceColumn\":\"고객번호\",\"targetField\":\"customerSourceId\"},"
            + "{\"sourceColumn\":\"성명\",\"targetField\":\"customerName\"}]}");
    long mappedVersion = batchVersion(batchId);

    MvcResult result =
        mockMvc
            .perform(
                jsonPutAs(
                    "/api/import-batches/" + batchId + "/sheet",
                    "{\"sheetName\":\"매물\"}",
                    cookie,
                    mappedVersion))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UPLOADED"))
            .andExpect(jsonPath("$.selectedSheetName").value("매물"))
            .andExpect(jsonPath("$.sheets.length()").value(2))
            .andExpect(jsonPath("$.version").value(mappedVersion + 1))
            .andReturn();
    assertThat(AuthFlowSupport.json(result).get("fileName").asString()).isEqualTo("a.xlsx");

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/mapping-suggestion", cookie))
        .andExpect(jsonPath("$.sourceColumns.length()").value(3))
        .andExpect(jsonPath("$.sourceColumns[0].name").value("매물번호"));
    assertError(
        mockMvc.perform(authedGet("/api/import-batches/" + batchId + "/preview", cookie)),
        ErrorCode.INVALID_TRANSITION);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT row_count FROM import_batch WHERE id = ?", Integer.class, batchId))
        .isNull();
  }

  @Test
  void 같은_시트를_고르면_버전을_올리지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "1,홍길동,,,");
    long version = batchVersion(batchId);

    mockMvc
        .perform(
            jsonPutAs(
                "/api/import-batches/" + batchId + "/sheet",
                "{\"sheetName\":\"Sheet1\"}",
                cookie,
                version))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(version));
  }

  @Test
  void 시트_변경_오류_경우를_모두_거절한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    var agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "1,홍길동,,,");
    String url = "/api/import-batches/" + batchId + "/sheet";
    long version = batchVersion(batchId);

    assertFieldError(
        mockMvc.perform(jsonPutAs(url, "{\"sheetName\":\"없는시트\"}", cookie, version)),
        "sheetName",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(jsonPutAs(url, "{}", cookie, version)), "sheetName", "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPutAs(url, "{\"sheetName\":\"Sheet1\",\"x\":1}", cookie, version)),
        "x",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(jsonPutAs(url, "{\"sheetName\":\"Sheet1\"}", cookie, null)),
        "If-Match",
        "REQUIRED");
    mockMvc
        .perform(jsonPutAs(url, "{\"sheetName\":\"Sheet1\"}", cookie, version + 3))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    assertError(
        mockMvc.perform(jsonPutAs(url, "{\"sheetName\":\"Sheet1\"}", cookieOf(other), version)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPutAs(url, "{\"sheetName\":\"Sheet1\"}", cookieOf(agent), version)),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(jsonPutAs(url, "{\"sheetName\":\"Sheet1\"}", null, version)),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 시트_변경은_UPLOADED_MAPPING_PREVIEW에서만_가능하다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "1,홍길동,,,");
    String url = "/api/import-batches/" + batchId + "/sheet";

    for (String status : List.of("EXECUTING", "COMPLETED", "FAILED", "CANCELLED")) {
      forceStatus(batchId, status);
      assertError(
          mockMvc.perform(
              jsonPutAs(url, "{\"sheetName\":\"Sheet1\"}", cookie, batchVersion(batchId))),
          ErrorCode.INVALID_TRANSITION);
    }
    for (String status : List.of("MAPPING", "PREVIEW")) {
      forceStatus(batchId, status);
      mockMvc
          .perform(jsonPutAs(url, "{\"sheetName\":\"Sheet1\"}", cookie, batchVersion(batchId)))
          .andExpect(status().isOk());
    }
  }

  @Test
  void 유료_이전_지원에_연결된_실행_단위는_시트도_바꿀_수_없다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = uploadCustomers(cookie, "1,홍길동,,,");
    insertSupportRequest(owner.office().getId(), batchId, "QUOTED");

    assertError(
        mockMvc.perform(
            jsonPutAs(
                "/api/import-batches/" + batchId + "/sheet",
                "{\"sheetName\":\"Sheet1\"}",
                cookie,
                batchVersion(batchId))),
        ErrorCode.IMPORT_MANAGED_BY_SUPPORT);
  }

  private long insertOperator() {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO operator_account (kakao_provider_key, name, status,"
                + " totp_secret_encrypted) VALUES ('op-key', '운영자', 'ACTIVE', 'secret')"
                + " RETURNING id",
            Long.class);
    return id == null ? -1 : id;
  }
}
