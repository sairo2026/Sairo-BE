package com.sairo.be.domain.migration.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.ImportIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ImportExecuteIntegrationTest extends ImportIntegrationTestBase {

  @Test
  void 실행하면_EXECUTING으로_응답하고_끝난_뒤_COMPLETED가_된다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,01012345678,a@b.co,메모", "C-2,김철수,,,");
    long versionBefore = batchVersion(batchId);

    mockMvc
        .perform(jsonPostAs("/api/import-batches/" + batchId + "/execute", "", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.batchId").value(batchId))
        .andExpect(jsonPath("$.status").value("EXECUTING"))
        .andExpect(jsonPath("$.cancellableUntil").doesNotExist())
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.version").value(versionBefore + 1))
        .andExpect(jsonPath("$.createdAt").exists())
        .andExpect(jsonPath("$.updatedAt").exists());

    assertThat(batchStatus(batchId)).isEqualTo("COMPLETED");
    JsonNode detail = detail(cookie, batchId);
    assertThat(detail.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(detail.get("counts").get("total").asInt()).isEqualTo(2);
    assertThat(detail.get("counts").get("succeeded").asInt()).isEqualTo(2);
    assertThat(detail.get("counts").get("failed").asInt()).isZero();
    assertThat(detail.get("counts").get("unprocessed").asInt()).isZero();
    assertThat(detail.get("executedAt").isNull()).isFalse();
    assertThat(detail.get("cancellableUntil").isNull()).isFalse();
    assertThat(detail.get("failureReason").isNull()).isTrue();
    assertThat(count("customer")).isEqualTo(2);
    assertThat(count("import_record_link")).isEqualTo(2);
    assertThat(count("import_row_result")).isEqualTo(2);
  }

  @Test
  void 새_고객은_담당자_수집근거_이전_목적_보유기한을_기록하고_IMPORT_생성_사건을_남긴다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,01012345678,a@b.co,첫 메모");

    executeBatch(cookie, batchId);

    Map<String, Object> customer = jdbcTemplate.queryForMap("SELECT * FROM customer");
    assertThat(customer)
        .containsEntry("name", "홍길동")
        .containsEntry("phone", "010-1234-5678")
        .containsEntry("email", "a@b.co")
        .containsEntry("memo", "첫 메모")
        .containsEntry("is_corporation", false)
        .containsEntry("managing_membership_id", owner.membership().getId())
        .containsEntry("data_source", "IMPORT")
        .containsEntry("processing_basis", "LEGITIMATE_INTEREST");
    assertThat((String) customer.get("data_purpose")).isNotBlank();
    LocalDate retention = ((java.sql.Date) customer.get("retention_until")).toLocalDate();
    assertThat(retention).isAfter(LocalDate.now().plusYears(2));
    Map<String, Object> event =
        jdbcTemplate.queryForMap(
            "SELECT event_type, entity_type, creation_source, recorded_by_membership_id,"
                + " customer_id FROM entity_lifecycle_event");
    assertThat(event)
        .containsEntry("event_type", "CREATED")
        .containsEntry("entity_type", "CUSTOMER")
        .containsEntry("creation_source", "IMPORT")
        .containsEntry("recorded_by_membership_id", owner.membership().getId())
        .containsEntry("customer_id", customer.get("id"));
    Map<String, Object> link = jdbcTemplate.queryForMap("SELECT * FROM import_record_link");
    assertThat(link)
        .containsEntry("source_system", "양셀")
        .containsEntry("source_type", "CUSTOMER")
        .containsEntry("source_id", "C-1")
        .containsEntry("target_entity_type", "CUSTOMER")
        .containsEntry("target_entity_id", customer.get("id"))
        .containsEntry("batch_id", batchId);
  }

  @Test
  void 새_매물은_초안으로_만들고_단위를_변환해_저장하며_IMPORT_생성_사건을_남긴다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyPropertyBatch(cookie, "P1,역삼 빌딩,서울 강남구 1,101호,30,5,2026.3.5,공실");

    executeBatch(cookie, batchId);

    Map<String, Object> property = jdbcTemplate.queryForMap("SELECT * FROM property");
    assertThat(property)
        .containsEntry("name", "역삼 빌딩")
        .containsEntry("address", "서울 강남구 1")
        .containsEntry("address_detail", "101호")
        .containsEntry("registration_stage", "DRAFT")
        .containsEntry("occupancy_status", "VACANT")
        .containsEntry("maintenance_fee_amount", 50_000L)
        .containsEntry("managing_membership_id", owner.membership().getId());
    assertThat((BigDecimal) property.get("exclusive_area_sqm")).isEqualByComparingTo("99.17");
    assertThat(property.get("move_in_date").toString()).isEqualTo("2026-03-05");
    Map<String, Object> event =
        jdbcTemplate.queryForMap(
            "SELECT event_type, entity_type, creation_source FROM entity_lifecycle_event");
    assertThat(event)
        .containsEntry("event_type", "CREATED")
        .containsEntry("entity_type", "PROPERTY")
        .containsEntry("creation_source", "IMPORT");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT c.code FROM property p JOIN property_category c ON c.id = p.category_id",
                String.class))
        .isEqualTo("COMMERCIAL_BUILDING");
  }

  @Test
  void 같은_주소의_매물이_이미_있어도_이전_매물은_등록_사유를_자동으로_남기고_만든다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    createProperty(cookie, "기존", categoryIdOf("APARTMENT"));
    jdbcTemplate.update("UPDATE property SET address = '서울 강남구 1', address_detail = '101호'");
    long batchId = readyPropertyBatch(cookie, "P1,이전 빌딩,서울 강남구 1,101호,,,,");

    executeBatch(cookie, batchId);

    assertThat(count("property")).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT duplicate_override_reason FROM property WHERE name = '이전 빌딩'",
                String.class))
        .isNotBlank();
  }

  @Test
  void 오류가_있는_줄은_실패로_남기고_나머지는_반영한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId =
        readyCustomerBatch(cookie, "C-1,홍길동,,,", "C-2,김철수,,이메일아님,", "C-3,,,,", "C-4,이영희,,,");

    executeBatch(cookie, batchId);

    JsonNode detail = detail(cookie, batchId);
    assertThat(detail.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(detail.get("counts").get("succeeded").asInt()).isEqualTo(2);
    assertThat(detail.get("counts").get("failed").asInt()).isEqualTo(2);
    assertThat(detail.get("failureReason").asString()).contains("2건");
    assertThat(count("customer")).isEqualTo(2);
    List<Map<String, Object>> failed =
        jdbcTemplate.queryForList(
            "SELECT source_row_number, failure_reason FROM import_row_result WHERE result ="
                + " 'FAILED' ORDER BY source_row_number");
    assertThat(failed).extracting(row -> row.get("source_row_number")).containsExactly(3, 4);
    assertThat((String) failed.get(0).get("failure_reason")).contains("INVALID_EMAIL_FORMAT");
    assertThat((String) failed.get(1).get("failure_reason")).contains("REQUIRED_VALUE_MISSING");
  }

  @Test
  void 모든_줄이_실패하면_FAILED가_되고_취소_기한이_없다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,,,,", "C-2,,,,");

    executeBatch(cookie, batchId);

    JsonNode detail = detail(cookie, batchId);
    assertThat(detail.get("status").asString()).isEqualTo("FAILED");
    assertThat(detail.get("cancellableUntil").isNull()).isTrue();
    assertThat(detail.get("counts").get("failed").asInt()).isEqualTo(2);
    assertThat(detail.get("failureReason").isNull()).isFalse();
    assertThat(count("customer")).isZero();
  }

  @Test
  void 이미_연결된_원본은_새로_만들지_않고_기존_레코드에_연결한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long first = readyCustomerBatch(cookie, "C-1,홍길동,,,", "C-2,김철수,,,");
    executeBatch(cookie, first);
    long second = readyCustomerBatch(cookie, "C-1,홍길동 수정,,,", "C-3,이영희,,,");

    executeBatch(cookie, second);

    assertThat(count("customer")).isEqualTo(3);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT name FROM customer WHERE id = (SELECT target_entity_id FROM"
                    + " import_record_link WHERE source_id = 'C-1')",
                String.class))
        .isEqualTo("홍길동");
    JsonNode detail = detail(cookie, second);
    assertThat(detail.get("counts").get("succeeded").asInt()).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM import_record_link WHERE batch_id = ?", Long.class, second))
        .isEqualTo(1);
  }

  @Test
  void 같은_파일_안에서_같은_원본_ID가_두_번_나오면_한_번만_만든다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,", "C-1,홍길동 중복,,,");

    executeBatch(cookie, batchId);

    assertThat(count("customer")).isEqualTo(1);
    assertThat(detail(cookie, batchId).get("counts").get("succeeded").asInt()).isEqualTo(2);
  }

  @Test
  void 월_5회를_다_쓰면_429_IMPORT_QUOTA_EXCEEDED이고_반영된_묶음만_센다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,");
    useUpMonthlyQuota(owner, 4);

    executeBatch(cookie, batchId);
    long another = readyCustomerBatch(cookie, "C-9,김철수,,,");

    assertError(
        mockMvc.perform(jsonPostAs("/api/import-batches/" + another + "/execute", "", cookie)),
        ErrorCode.IMPORT_QUOTA_EXCEEDED);
    assertThat(batchStatus(another)).isEqualTo("MAPPING");
    assertThat(count("customer")).isEqualTo(1);
  }

  @Test
  void 실제로_반영되지_않은_묶음과_유료_지원_묶음은_한도에_세지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    for (int i = 0; i < 4; i++) {
      long id = insertExecutedBatch(owner, "q" + i);
      jdbcTemplate.update("DELETE FROM import_record_link WHERE batch_id = ?", id);
    }
    long supported = insertExecutedBatch(owner, "s1");
    insertSupportRequest(owner.office().getId(), supported, "REQUESTED");
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,");

    executeBatch(cookie, batchId);

    assertThat(batchStatus(batchId)).isEqualTo("COMPLETED");
  }

  @Test
  void 다른_사무소의_실행_횟수는_세지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    useUpMonthlyQuota(other, 5);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,");

    executeBatch(cookie, batchId);

    assertThat(batchStatus(batchId)).isEqualTo("COMPLETED");
  }

  @Test
  void 매핑_전이거나_이미_실행한_실행_단위는_409_INVALID_TRANSITION이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long uploaded = uploadCustomers(cookie, "C-1,홍길동,,,");
    long done = readyCustomerBatch(cookie, "C-2,김철수,,,");
    executeBatch(cookie, done);

    assertError(
        mockMvc.perform(jsonPostAs("/api/import-batches/" + uploaded + "/execute", "", cookie)),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(jsonPostAs("/api/import-batches/" + done + "/execute", "", cookie)),
        ErrorCode.INVALID_TRANSITION);
    assertThat(count("customer")).isEqualTo(1);
  }

  @Test
  void 유료_이전_지원에_연결된_실행_단위는_직접_실행할_수_없다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,");
    insertSupportRequest(owner.office().getId(), batchId, "ACCEPTED");

    assertError(
        mockMvc.perform(jsonPostAs("/api/import-batches/" + batchId + "/execute", "", cookie)),
        ErrorCode.IMPORT_MANAGED_BY_SUPPORT);
    assertThat(count("customer")).isZero();
  }

  @Test
  void If_Match를_보내면_검사한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,");

    mockMvc
        .perform(
            jsonPostAs("/api/import-batches/" + batchId + "/execute", "", cookie)
                .header("If-Match", batchVersion(batchId) + 4))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    mockMvc
        .perform(
            jsonPostAs("/api/import-batches/" + batchId + "/execute", "", cookie)
                .header("If-Match", batchVersion(batchId)))
        .andExpect(status().isOk());
  }

  @Test
  void 실행_권한과_다른_사무소_경계를_지킨다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    var agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    long batchId = readyCustomerBatch(cookieOf(owner), "C-1,홍길동,,,");
    String url = "/api/import-batches/" + batchId + "/execute";

    assertError(
        mockMvc.perform(jsonPostAs(url, "", cookieOf(other))), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(jsonPostAs(url, "", cookieOf(agent))), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(jsonPostAs(url, "", null)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPostAs("/api/import-batches/99999/execute", "", cookieOf(owner))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertThat(count("customer")).isZero();
  }

  @Test
  void 실패한_줄만_같은_묶음_안에서_다시_처리하고_실행_횟수를_더_쓰지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,", "C-2,김철수,,이메일아님,", "C-3,이영희,,,");
    executeBatch(cookie, batchId);
    String key =
        jdbcTemplate.queryForObject("SELECT file_object_key FROM import_batch", String.class);
    objectStorage.put(
        key, customerCsv("C-1,홍길동,,,", "C-2,김철수,,fixed@example.com,", "C-3,이영희,,,"), "text/csv");
    long remainingBefore = remaining(cookie);
    long versionBefore = batchVersion(batchId);

    MvcResult result =
        mockMvc
            .perform(jsonPostAs("/api/import-batches/" + batchId + "/retry-failed", "", cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.batchId").value(batchId))
            .andExpect(jsonPath("$.status").value("EXECUTING"))
            .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
            .andReturn();

    assertThat(AuthFlowSupport.json(result).get("version").longValue())
        .isEqualTo(versionBefore + 1);
    JsonNode detail = detail(cookie, batchId);
    assertThat(detail.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(detail.get("counts").get("succeeded").asInt()).isEqualTo(3);
    assertThat(detail.get("counts").get("failed").asInt()).isZero();
    assertThat(detail.get("failureReason").isNull()).isTrue();
    assertThat(count("customer")).isEqualTo(3);
    assertThat(count("import_row_result")).isEqualTo(3);
    assertThat(remaining(cookie)).isEqualTo(remainingBefore);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT email FROM customer WHERE name = '김철수'", String.class))
        .isEqualTo("fixed@example.com");
  }

  @Test
  void 재처리해도_계속_실패하면_실패_건수가_그대로이고_성공한_줄은_다시_만들지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,", "C-2,,,,");
    executeBatch(cookie, batchId);

    mockMvc
        .perform(jsonPostAs("/api/import-batches/" + batchId + "/retry-failed", "", cookie))
        .andExpect(status().isOk());

    JsonNode detail = detail(cookie, batchId);
    assertThat(detail.get("counts").get("succeeded").asInt()).isEqualTo(1);
    assertThat(detail.get("counts").get("failed").asInt()).isEqualTo(1);
    assertThat(count("customer")).isEqualTo(1);
  }

  @Test
  void 재처리할_수_없는_상태와_실패가_없는_묶음은_409_INVALID_TRANSITION이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long uploaded = uploadCustomers(cookie, "C-1,홍길동,,,");
    long done = readyCustomerBatch(cookie, "C-2,김철수,,,");
    executeBatch(cookie, done);

    assertError(
        mockMvc.perform(
            jsonPostAs("/api/import-batches/" + uploaded + "/retry-failed", "", cookie)),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(jsonPostAs("/api/import-batches/" + done + "/retry-failed", "", cookie)),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 재처리도_유료_이전_지원_연결_권한_다른_사무소_경계를_지킨다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    var agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,,,,");
    executeBatch(cookie, batchId);
    String url = "/api/import-batches/" + batchId + "/retry-failed";

    assertError(
        mockMvc.perform(jsonPostAs(url, "", cookieOf(other))), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(jsonPostAs(url, "", cookieOf(agent))), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(jsonPostAs(url, "", null)), ErrorCode.UNAUTHENTICATED);
    insertSupportRequest(owner.office().getId(), batchId, "REQUESTED");
    assertError(mockMvc.perform(jsonPostAs(url, "", cookie)), ErrorCode.IMPORT_MANAGED_BY_SUPPORT);
  }

  private long remaining(Cookie cookie) throws Exception {
    MvcResult result =
        mockMvc
            .perform(authedGet("/api/import-batches", cookie))
            .andExpect(status().isOk())
            .andReturn();
    return AuthFlowSupport.json(result).get("remainingThisMonth").longValue();
  }
}
