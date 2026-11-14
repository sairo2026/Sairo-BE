package com.sairo.be.domain.migration.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.ImportIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

@IntegrationTest
class ImportPreviewIntegrationTest extends ImportIntegrationTestBase {

  @Test
  void 미리보기는_정규화_값과_원본_값을_돌려주고_어떤_데이터도_만들지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId =
        readyCustomerBatch(cookie, "1,홍길동,01012345678,a@b.co,첫 메모", "2,김철수,010-3333-4444,,");
    long versionBefore = batchVersion(batchId);

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/preview", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.rows.length()").value(2))
        .andExpect(jsonPath("$.rows[0].sourceRowNumber").value(2))
        .andExpect(jsonPath("$.rows[0].sourceValues['성명']").value("홍길동"))
        .andExpect(jsonPath("$.rows[0].sourceValues['연락처']").value("01012345678"))
        .andExpect(jsonPath("$.rows[0].normalizedValues.customerName").value("홍길동"))
        .andExpect(jsonPath("$.rows[0].normalizedValues.customerPhone").value("010-1234-5678"))
        .andExpect(jsonPath("$.rows[0].unitConversions.length()").value(0))
        .andExpect(jsonPath("$.rows[0].warnings.length()").value(0))
        .andExpect(jsonPath("$.rows[1].sourceRowNumber").value(3))
        .andExpect(jsonPath("$.rows[1].sourceValues['이메일']").doesNotExist())
        .andExpect(jsonPath("$.duplicateWarnings.length()").value(0))
        .andExpect(jsonPath("$.summary.rowCount").value(2))
        .andExpect(jsonPath("$.summary.errorRowCount").value(0))
        .andExpect(jsonPath("$.summary.warningCount").value(0))
        .andExpect(jsonPath("$.summary.duplicateCount").value(0))
        .andExpect(jsonPath("$.summary.relationMatchCount").value(0));

    assertThat(count("customer")).isZero();
    assertThat(count("property")).isZero();
    assertThat(count("import_record_link")).isZero();
    assertThat(count("import_row_result")).isZero();
    assertThat(count("entity_lifecycle_event")).isZero();
    assertThat(batchStatus(batchId)).isEqualTo("PREVIEW");
    assertThat(batchVersion(batchId)).isEqualTo(versionBefore);
  }

  @Test
  void 매물_미리보기는_평과_만원_단위를_변환하고_변환_내역을_담는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyPropertyBatch(cookie, "P1,역삼 빌딩,서울 강남구 1,101호,30,5,2026.3.5,공실");

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/preview", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].normalizedValues.exclusiveAreaSqm").value(99.17))
        .andExpect(jsonPath("$.rows[0].normalizedValues.maintenanceFeeAmount").value(50000))
        .andExpect(jsonPath("$.rows[0].normalizedValues.moveInDate").value("2026-03-05"))
        .andExpect(jsonPath("$.rows[0].normalizedValues.occupancyStatus").value("VACANT"))
        .andExpect(jsonPath("$.rows[0].unitConversions.length()").value(2))
        .andExpect(jsonPath("$.rows[0].unitConversions[0].field").value("exclusiveAreaSqm"))
        .andExpect(jsonPath("$.rows[0].unitConversions[0].sourceValue").value("30"))
        .andExpect(jsonPath("$.rows[0].unitConversions[0].targetValue").value(99.17))
        .andExpect(jsonPath("$.rows[0].unitConversions[0].ruleCode").value("PYEONG_TO_SQM"))
        .andExpect(jsonPath("$.rows[0].unitConversions[1].ruleCode").value("MAN_WON_TO_WON"));
  }

  @Test
  void 오류와_경고는_행마다_심각도와_함께_돌려주고_요약에_센다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId =
        readyCustomerBatch(
            cookie,
            "1,홍길동,02-12,a@b.co,",
            "2,김철수,010-3333-4444,이메일아님,",
            "3,,010-5555-6666,,",
            "4,이영희,010-7777-8888,,");

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/preview", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].warnings[0].code").value("INVALID_PHONE_FORMAT"))
        .andExpect(jsonPath("$.rows[0].warnings[0].field").value("customerPhone"))
        .andExpect(jsonPath("$.rows[0].warnings[0].severity").value("WARNING"))
        .andExpect(jsonPath("$.rows[1].warnings[0].code").value("INVALID_EMAIL_FORMAT"))
        .andExpect(jsonPath("$.rows[1].warnings[0].severity").value("ERROR"))
        .andExpect(jsonPath("$.rows[2].warnings[0].code").value("REQUIRED_VALUE_MISSING"))
        .andExpect(jsonPath("$.rows[2].warnings[0].field").value("customerName"))
        .andExpect(jsonPath("$.rows[3].warnings.length()").value(0))
        .andExpect(jsonPath("$.summary.errorRowCount").value(2))
        .andExpect(jsonPath("$.summary.warningCount").value(1));
  }

  @Test
  void page와_size로_나누어_받고_size는_25_50_100만_허용한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    String[] rows = new String[30];
    for (int i = 0; i < rows.length; i++) {
      rows[i] = (i + 1) + ",고객" + (i + 1) + ",,,";
    }
    long batchId = readyCustomerBatch(cookie, rows);
    String url = "/api/import-batches/" + batchId + "/preview";

    mockMvc
        .perform(authedGet(url, cookie))
        .andExpect(jsonPath("$.rows", hasSize(25)))
        .andExpect(jsonPath("$.totalElements").value(30));
    mockMvc
        .perform(authedGet(url, cookie).param("page", "1"))
        .andExpect(jsonPath("$.rows", hasSize(5)))
        .andExpect(jsonPath("$.rows[0].sourceRowNumber").value(27))
        .andExpect(jsonPath("$.page").value(1));
    mockMvc
        .perform(authedGet(url, cookie).param("size", "50"))
        .andExpect(jsonPath("$.rows", hasSize(30)))
        .andExpect(jsonPath("$.size").value(50));
    mockMvc
        .perform(authedGet(url, cookie).param("page", "5"))
        .andExpect(jsonPath("$.rows", hasSize(0)))
        .andExpect(jsonPath("$.totalElements").value(30));
    assertFieldError(
        mockMvc.perform(authedGet(url, cookie).param("size", "30")), "size", "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(authedGet(url, cookie).param("page", "-1")), "page", "OUT_OF_RANGE");
  }

  @Test
  void 이미_연결된_원본_ID는_중복_경고로_알리고_같은_파일_안_중복도_경고한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long existing = insertCustomer(owner.office().getId(), owner.membership().getId(), "기존");
    long firstBatch = insertExecutedBatch(owner, "unused");
    jdbcTemplate.update(
        "INSERT INTO import_record_link (batch_id, office_id, source_system, source_type,"
            + " source_id, target_entity_type, target_entity_id) VALUES (?, ?, '양셀', 'CUSTOMER',"
            + " 'C-1', 'CUSTOMER', ?)",
        firstBatch,
        owner.office().getId(),
        existing);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,", "C-2,김철수,,,", "C-2,김철수 중복,,,");

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/preview", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicateWarnings.length()").value(1))
        .andExpect(jsonPath("$.duplicateWarnings[0].sourceRowNumber").value(2))
        .andExpect(jsonPath("$.duplicateWarnings[0].sourceSystem").value("양셀"))
        .andExpect(jsonPath("$.duplicateWarnings[0].sourceType").value("CUSTOMER"))
        .andExpect(jsonPath("$.duplicateWarnings[0].sourceId").value("C-1"))
        .andExpect(jsonPath("$.duplicateWarnings[0].existingTargetId").value(existing))
        .andExpect(jsonPath("$.rows[2].warnings[0].code").value("DUPLICATE_SOURCE_ID_IN_FILE"))
        .andExpect(jsonPath("$.rows[2].warnings[0].severity").value("WARNING"))
        .andExpect(jsonPath("$.summary.duplicateCount").value(1))
        .andExpect(jsonPath("$.summary.warningCount").value(1));
  }

  @Test
  void 다른_원본_시스템의_같은_번호는_중복으로_보지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long existing = insertCustomer(owner.office().getId(), owner.membership().getId(), "기존");
    long firstBatch = insertExecutedBatch(owner, "unused");
    jdbcTemplate.update(
        "INSERT INTO import_record_link (batch_id, office_id, source_system, source_type,"
            + " source_id, target_entity_type, target_entity_id) VALUES (?, ?, '다른시스템',"
            + " 'CUSTOMER', 'C-1', 'CUSTOMER', ?)",
        firstBatch,
        owner.office().getId(),
        existing);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,");

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/preview", cookie))
        .andExpect(jsonPath("$.duplicateWarnings.length()").value(0))
        .andExpect(jsonPath("$.summary.duplicateCount").value(0));
  }

  @Test
  void 같은_이름과_전화번호의_기존_고객과_같은_주소의_기존_매물을_관계_후보로_보여준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long existingCustomer =
        createCustomer(cookie, "홍길동", "010-1234-5678", owner.membership().getId());
    long customerBatch = readyCustomerBatch(cookie, "1,홍길동,01012345678,,", "2,홍길동,,,", "3,김철수,,,");

    mockMvc
        .perform(authedGet("/api/import-batches/" + customerBatch + "/preview", cookie))
        .andExpect(jsonPath("$.rows[0].relationMatches.length()").value(1))
        .andExpect(jsonPath("$.rows[0].relationMatches[0].sourceField").value("성명"))
        .andExpect(jsonPath("$.rows[0].relationMatches[0].targetType").value("CUSTOMER"))
        .andExpect(jsonPath("$.rows[0].relationMatches[0].targetId").value(existingCustomer))
        .andExpect(jsonPath("$.rows[0].relationMatches[0].confidence").value(0.9))
        .andExpect(jsonPath("$.rows[1].relationMatches[0].confidence").value(0.5))
        .andExpect(jsonPath("$.rows[2].relationMatches.length()").value(0))
        .andExpect(jsonPath("$.summary.relationMatchCount").value(2));

    mockMvc.perform(
        jsonPostAs(
            "/api/properties",
            "{\"name\":\"기존 빌딩\",\"address\":\"서울 강남구 1\",\"addressDetail\":\"101호\","
                + "\"categoryId\":"
                + categoryIdOf("APARTMENT")
                + "}",
            cookie));
    long propertyBatch =
        readyPropertyBatch(
            cookie, "P1,빌딩,서울 강남구 1,101호,,,,", "P2,빌딩2,서울강남구1,,,,,", "P3,빌딩3,부산,,,,,");
    mockMvc
        .perform(authedGet("/api/import-batches/" + propertyBatch + "/preview", cookie))
        .andExpect(jsonPath("$.rows[0].relationMatches[0].targetType").value("PROPERTY"))
        .andExpect(jsonPath("$.rows[0].relationMatches[0].sourceField").value("주소"))
        .andExpect(jsonPath("$.rows[0].relationMatches[0].confidence").value(0.9))
        .andExpect(jsonPath("$.rows[1].relationMatches[0].confidence").value(0.4))
        .andExpect(jsonPath("$.rows[2].relationMatches.length()").value(0))
        .andExpect(jsonPath("$.summary.relationMatchCount").value(2));
  }

  @Test
  void 매핑_확정_전이거나_실행_뒤에는_미리보기를_할_수_없다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long uploaded = uploadCustomers(cookie, "1,홍길동,,,");
    long ready = readyCustomerBatch(cookie, "1,홍길동,,,");

    assertError(
        mockMvc.perform(authedGet("/api/import-batches/" + uploaded + "/preview", cookie)),
        ErrorCode.INVALID_TRANSITION);
    forceStatus(ready, "COMPLETED");
    assertError(
        mockMvc.perform(authedGet("/api/import-batches/" + ready + "/preview", cookie)),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 유료_이전_지원에_연결된_실행_단위도_미리보기는_볼_수_있다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "1,홍길동,,,");
    insertSupportRequest(owner.office().getId(), batchId, "QUOTED");

    mockMvc
        .perform(authedGet("/api/import-batches/" + batchId + "/preview", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void 미리보기_권한과_다른_사무소_경계를_지킨다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    var agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    long batchId = readyCustomerBatch(cookieOf(owner), "1,홍길동,,,");
    String url = "/api/import-batches/" + batchId + "/preview";

    assertError(mockMvc.perform(authedGet(url, cookieOf(other))), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(authedGet(url, cookieOf(agent))), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(authedGet(url, null)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(authedGet("/api/import-batches/99999/preview", cookieOf(owner))),
        ErrorCode.RESOURCE_NOT_FOUND);
  }
}
