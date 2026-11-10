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
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ImportCancelIntegrationTest extends ImportIntegrationTestBase {

  private long customerBySource(String sourceId) {
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT target_entity_id FROM import_record_link WHERE source_id = ?",
            Long.class,
            sourceId);
    return id == null ? -1 : id;
  }

  private JsonNode cancel(Cookie cookie, long batchId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(jsonPostAs("/api/import-batches/" + batchId + "/cancel", "", cookie))
            .andExpect(status().isOk())
            .andReturn();
    return AuthFlowSupport.json(result);
  }

  private long insertContractWithParty(TestData.Staff owner, Cookie cookie, long customerId)
      throws Exception {
    long property = createProperty(cookie, "계약 매물", categoryIdOf("APARTMENT"));
    Long contract =
        jdbcTemplate.queryForObject(
            "INSERT INTO contract (office_id, property_id, deal_type, managing_membership_id)"
                + " VALUES (?, ?, 'SALE', ?) RETURNING id",
            Long.class,
            owner.office().getId(),
            property,
            owner.membership().getId());
    jdbcTemplate.update(
        "INSERT INTO contract_party (contract_id, side, sequence_no, name, customer_id) VALUES"
            + " (?, 'SELLER_LANDLORD', 1, '당사자', ?)",
        contract,
        customerId);
    return contract == null ? -1 : contract;
  }

  @Test
  void 완료_후_24시간_이내에_취소하면_새로_만든_데이터와_연결을_제거한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,", "C-2,김철수,,,");
    executeBatch(cookie, batchId);
    long versionBefore = batchVersion(batchId);

    mockMvc
        .perform(jsonPostAs("/api/import-batches/" + batchId + "/cancel", "", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.batchId").value(batchId))
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.cancelledCount").value(2))
        .andExpect(jsonPath("$.notCancelled.length()").value(0))
        .andExpect(jsonPath("$.rowCount").value(2))
        .andExpect(jsonPath("$.executedAt").exists())
        .andExpect(jsonPath("$.cancellableUntil").doesNotExist())
        .andExpect(jsonPath("$.cancelledAt").exists())
        .andExpect(jsonPath("$.version").value(versionBefore + 1));

    assertThat(count("customer")).isZero();
    assertThat(count("import_record_link")).isZero();
    assertThat(count("entity_lifecycle_event")).isZero();
    assertThat(batchStatus(batchId)).isEqualTo("CANCELLED");
    JsonNode detail = detail(cookie, batchId);
    assertThat(detail.get("status").asString()).isEqualTo("CANCELLED");
    assertThat(detail.get("cancelledAt").isNull()).isFalse();
    assertThat(detail.get("cancelSummary").get("cancelledCount").asInt()).isEqualTo(2);
    assertThat(detail.get("cancelSummary").get("notCancelled")).isEmpty();
    assertThat(detail.get("counts").get("succeeded").asInt()).isEqualTo(2);
  }

  @Test
  void 매물도_취소하면_초안_행과_생성_사건까지_지운다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyPropertyBatch(cookie, "P1,빌딩,서울,101호,30,5,2026.3.5,공실");
    executeBatch(cookie, batchId);

    cancel(cookie, batchId);

    assertThat(count("property")).isZero();
    assertThat(count("entity_lifecycle_event")).isZero();
    assertThat(count("import_record_link")).isZero();
  }

  @Test
  void 후속수정_법정보존_외부연결이_생긴_항목은_제외하고_사유와_함께_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId =
        readyCustomerBatch(cookie, "C-1,상담있음,,,", "C-2,계약있음,,,", "C-3,조율있음,,,", "C-4,그대로,,,");
    executeBatch(cookie, batchId);
    long edited = customerBySource("C-1");
    long contracted = customerBySource("C-2");
    long coordinated = customerBySource("C-3");
    long untouched = customerBySource("C-4");
    createConsultationRow(edited, owner.membership().getId(), "FIRST_INQUIRY");
    insertContractWithParty(owner, cookie, contracted);
    jdbcTemplate.update(
        "INSERT INTO visit_coordination (office_id, customer_id, manager_membership_id, status,"
            + " period_start_date, period_end_date) VALUES (?, ?, ?, 'COORDINATABLE',"
            + " '2026-12-01', '2026-12-05')",
        owner.office().getId(),
        coordinated,
        owner.membership().getId());

    JsonNode body = cancel(cookie, batchId);

    assertThat(body.get("cancelledCount").asInt()).isEqualTo(1);
    Map<Long, String> reasons = new java.util.HashMap<>();
    body.get("notCancelled")
        .forEach(
            item -> {
              assertThat(item.get("entityType").asString()).isEqualTo("CUSTOMER");
              reasons.put(item.get("entityId").longValue(), item.get("reason").asString());
            });
    assertThat(reasons)
        .containsEntry(edited, "FOLLOW_UP_EDIT")
        .containsEntry(contracted, "LEGAL_RETENTION")
        .containsEntry(coordinated, "EXTERNAL_LINK")
        .hasSize(3);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customer WHERE id = ?", Long.class, untouched))
        .isZero();
    assertThat(count("customer")).isEqualTo(3);
    assertThat(count("import_record_link")).isEqualTo(3);
    JsonNode detail = detail(cookie, batchId);
    assertThat(detail.get("cancelSummary").get("cancelledCount").asInt()).isEqualTo(1);
    assertThat(detail.get("cancelSummary").get("notCancelled")).hasSize(3);
  }

  @Test
  void 고객_정보를_수정하면_후속수정으로_보고_취소에서_제외한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,", "C-2,김철수,,,");
    executeBatch(cookie, batchId);
    long customer = customerBySource("C-1");
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/customers/" + customer,
                "{\"memo\":\"수정\"}",
                cookie,
                customerVersion(customer)))
        .andExpect(status().isOk());

    JsonNode body = cancel(cookie, batchId);

    assertThat(body.get("cancelledCount").asInt()).isEqualTo(1);
    assertThat(body.get("notCancelled")).hasSize(1);
    assertThat(body.get("notCancelled").get(0).get("entityId").longValue()).isEqualTo(customer);
    assertThat(body.get("notCancelled").get(0).get("reason").asString())
        .isEqualTo("FOLLOW_UP_EDIT");
  }

  @Test
  void 매물에_계약이_있으면_법정보존_조율_후보면_외부연결_사진이나_수정이_있으면_후속수정으로_제외한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId =
        readyPropertyBatch(
            cookie, "P1,계약,서울 1,,,,,", "P2,조율,서울 2,,,,,", "P3,수정,서울 3,,,,,", "P4,그대로,서울 4,,,,,");
    executeBatch(cookie, batchId);
    long contracted = customerBySource("P1");
    long coordinated = customerBySource("P2");
    long edited = customerBySource("P3");
    jdbcTemplate.update(
        "INSERT INTO contract (office_id, property_id, deal_type, managing_membership_id) VALUES"
            + " (?, ?, 'SALE', ?)",
        owner.office().getId(),
        contracted,
        owner.membership().getId());
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "고객");
    long coordination =
        jdbcTemplate.queryForObject(
            "INSERT INTO visit_coordination (office_id, customer_id, manager_membership_id, status,"
                + " period_start_date, period_end_date) VALUES (?, ?, ?, 'COORDINATABLE',"
                + " '2026-12-01', '2026-12-05') RETURNING id",
            Long.class,
            owner.office().getId(),
            customer,
            owner.membership().getId());
    jdbcTemplate.update(
        "INSERT INTO visit_candidate_property (coordination_id, office_id, property_id,"
            + " property_office_id, sort_order) VALUES (?, ?, ?, ?, 1)",
        coordination,
        owner.office().getId(),
        coordinated,
        owner.office().getId());
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/properties/" + edited, "{\"memo\":\"수정\"}", cookie, propertyVersion(edited)))
        .andExpect(status().isOk());

    JsonNode body = cancel(cookie, batchId);

    Map<Long, String> reasons = new java.util.HashMap<>();
    body.get("notCancelled")
        .forEach(
            item -> reasons.put(item.get("entityId").longValue(), item.get("reason").asString()));
    assertThat(reasons)
        .containsEntry(contracted, "LEGAL_RETENTION")
        .containsEntry(coordinated, "EXTERNAL_LINK")
        .containsEntry(edited, "FOLLOW_UP_EDIT")
        .hasSize(3);
    assertThat(body.get("cancelledCount").asInt()).isEqualTo(1);
    assertThat(body.get("notCancelled").get(0).get("entityType").asString()).isEqualTo("PROPERTY");
  }

  @Test
  void 취소할_수_있는_신규_데이터가_하나도_없으면_409_IMPORT_NOT_CANCELLABLE이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,");
    executeBatch(cookie, batchId);
    createConsultationRow(customerBySource("C-1"), owner.membership().getId(), "FIRST_INQUIRY");

    assertError(
        mockMvc.perform(jsonPostAs("/api/import-batches/" + batchId + "/cancel", "", cookie)),
        ErrorCode.IMPORT_NOT_CANCELLABLE);
    assertThat(count("customer")).isEqualTo(1);
    assertThat(batchStatus(batchId)).isEqualTo("COMPLETED");
  }

  @Test
  void 같은_파일을_다시_올려_전부_기존_연결이면_취소할_신규_데이터가_없다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    executeBatch(cookie, readyCustomerBatch(cookie, "C-1,홍길동,,,"));
    long second = readyCustomerBatch(cookie, "C-1,홍길동,,,");
    executeBatch(cookie, second);

    assertError(
        mockMvc.perform(jsonPostAs("/api/import-batches/" + second + "/cancel", "", cookie)),
        ErrorCode.IMPORT_NOT_CANCELLABLE);
    assertThat(count("customer")).isEqualTo(1);
  }

  @Test
  void 취소_기한이_지나면_409_IMPORT_NOT_CANCELLABLE이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,");
    executeBatch(cookie, batchId);
    jdbcTemplate.update(
        "UPDATE import_batch SET executed_at = now() - interval '25 hours', cancellable_until ="
            + " now() - interval '1 hour' WHERE id = ?",
        batchId);

    assertError(
        mockMvc.perform(jsonPostAs("/api/import-batches/" + batchId + "/cancel", "", cookie)),
        ErrorCode.IMPORT_NOT_CANCELLABLE);
    assertThat(count("customer")).isEqualTo(1);
  }

  @Test
  void 완료되지_않았거나_이미_취소한_묶음은_409_IMPORT_NOT_CANCELLABLE이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long uploaded = uploadCustomers(cookie, "C-1,홍길동,,,");
    long done = readyCustomerBatch(cookie, "C-2,김철수,,,");
    executeBatch(cookie, done);
    cancel(cookie, done);

    assertError(
        mockMvc.perform(jsonPostAs("/api/import-batches/" + uploaded + "/cancel", "", cookie)),
        ErrorCode.IMPORT_NOT_CANCELLABLE);
    assertError(
        mockMvc.perform(jsonPostAs("/api/import-batches/" + done + "/cancel", "", cookie)),
        ErrorCode.IMPORT_NOT_CANCELLABLE);
  }

  @Test
  void 취소한_묶음도_이번_달_실행_횟수에_센다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,");
    executeBatch(cookie, batchId);
    cancel(cookie, batchId);

    mockMvc
        .perform(authedGet("/api/import-batches", cookie))
        .andExpect(jsonPath("$.remainingThisMonth").value(4))
        .andExpect(jsonPath("$.items[0].status").value("CANCELLED"));
  }

  @Test
  void 취소_If_Match를_보내면_검사하고_권한과_사무소_경계를_지킨다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    var agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,");
    executeBatch(cookie, batchId);
    String url = "/api/import-batches/" + batchId + "/cancel";

    mockMvc
        .perform(jsonPostAs(url, "", cookie).header("If-Match", batchVersion(batchId) + 2))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    assertError(
        mockMvc.perform(jsonPostAs(url, "", cookieOf(other))), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(jsonPostAs(url, "", cookieOf(agent))), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(jsonPostAs(url, "", null)), ErrorCode.UNAUTHENTICATED);
    assertThat(count("customer")).isEqualTo(1);
  }

  @Test
  void 취소_응답의_notCancelled는_제외가_없으면_빈_배열이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long batchId = readyCustomerBatch(cookie, "C-1,홍길동,,,");
    executeBatch(cookie, batchId);

    JsonNode body = cancel(cookie, batchId);

    assertThat(body.get("notCancelled").isArray()).isTrue();
    assertThat(body.get("notCancelled").size()).isZero();
  }
}
