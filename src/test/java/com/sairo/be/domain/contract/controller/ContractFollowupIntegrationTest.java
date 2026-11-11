package com.sairo.be.domain.contract.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.contract.service.ContractFollowupService;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@IntegrationTest
class ContractFollowupIntegrationTest extends ContractIntegrationTestBase {

  @Autowired private ContractFollowupService followupService;

  private long followups(long contractId) {
    return longValue(
        "SELECT count(*) FROM contract_followup_task WHERE contract_id = ? AND task_type ="
            + " 'D90_FOLLOWUP'",
        contractId);
  }

  private String taskStatus(String sourceType, long contractId) {
    var rows =
        jdbcTemplate.queryForList(
            "SELECT status FROM task WHERE source_type = ? AND source_id = ?",
            String.class,
            sourceType,
            contractId);
    assertThat(rows.size()).isLessThanOrEqualTo(1);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private LocalDate taskDue(String sourceType, long contractId) {
    return jdbcTemplate.queryForObject(
        "SELECT due_date FROM task WHERE source_type = ? AND source_id = ?",
        LocalDate.class,
        sourceType,
        contractId);
  }

  private void correct(long contractId, ObjectNode body) throws Exception {
    mockMvc
        .perform(
            jsonPostAs(
                    "/api/contracts/" + contractId + "/corrections", body.toString(), ownerCookie)
                .header("If-Match", contractVersion(contractId)))
        .andExpect(status().isCreated());
  }

  @Test
  void 체결_때_만기_90일_이하면_후속업무와_만기_업무를_바로_만들고_잔금_업무를_둔다() throws Exception {
    LocalDate end = today().plusDays(60);
    long near = createJeonse(property, today().minusDays(5), end);
    assertThat(followups(near)).isEqualTo(1);
    assertThat(taskStatus("CONTRACT_MATURITY", near)).isEqualTo("TODO");
    assertThat(taskDue("CONTRACT_MATURITY", near)).isEqualTo(end);
    assertThat(taskStatus("CONTRACT_BALANCE", near)).isEqualTo("TODO");
    assertThat(taskDue("CONTRACT_BALANCE", near)).isEqualTo(today().minusDays(5));
    JsonNode items =
        getJson("/api/contracts/" + near + "/followup-tasks", ownerCookie).get("items");
    assertThat(items.size()).isEqualTo(1);
    assertThat(items.get(0).get("taskType").asString()).isEqualTo("D90_FOLLOWUP");
    assertThat(items.get(0).get("targetDate").asString()).isEqualTo(end.toString());
    assertThat(items.get(0).get("submitted").booleanValue()).isFalse();
    assertThat(items.get(0).get("version").longValue()).isEqualTo(contractVersion(near));

    long farProperty = addressedProperty(ownerCookie, "먼 만기 매물", "서울시 마포구 합정동 2");
    long far = createJeonse(farProperty, today().minusDays(5), today().plusYears(2));
    assertThat(followups(far)).isZero();
    assertThat(taskStatus("CONTRACT_MATURITY", far)).isNull();
    assertThat(
            getJson("/api/contracts/" + far + "/followup-tasks", ownerCookie).get("items").size())
        .isZero();

    ObjectNode closer = correctionOf(far);
    closer.put("leaseEndDate", today().plusDays(30).toString());
    correct(far, closer);
    assertThat(followups(far)).isEqualTo(1);
    assertThat(taskDue("CONTRACT_MATURITY", far)).isEqualTo(today().plusDays(30));
    ObjectNode later = correctionOf(far);
    later.put("leaseEndDate", today().plusYears(2).toString());
    later.put("balanceDueDate", today().plusDays(7).toString());
    correct(far, later);
    assertThat(followups(far)).isZero();
    assertThat(taskStatus("CONTRACT_MATURITY", far)).isEqualTo("CANCELLED");
    assertThat(taskDue("CONTRACT_BALANCE", far)).isEqualTo(today().plusDays(7));

    long saleProperty = addressedProperty(ownerCookie, "매매 매물", "서울시 마포구 합정동 3");
    long saleId =
        create(saleProperty, sale(ownerMembership, today(), ""), ownerCookie)
            .get("contractId")
            .longValue();
    assertThat(followups(saleId)).isZero();
    assertThat(taskStatus("CONTRACT_BALANCE", saleId)).isNull();

    mockMvc
        .perform(
            jsonPatchAs(
                "/api/contracts/" + near + "/status",
                "{\"toStatus\":\"CANCELLED\",\"reason\":\"계약 취소\",\"effectiveAt\":\""
                    + Instant.now().minus(1, ChronoUnit.MINUTES).atZone(KST).toOffsetDateTime()
                    + "\"}",
                ownerCookie,
                contractVersion(near)))
        .andExpect(status().isOk());
    assertThat(taskStatus("CONTRACT_MATURITY", near)).isEqualTo("CANCELLED");
    assertThat(taskStatus("CONTRACT_BALANCE", near)).isEqualTo("CANCELLED");
    assertThat(followups(near)).isEqualTo(1);
  }

  @Test
  void D90_배치는_만기_90일_안에_들어온_계약만_한_번_만들고_재계약은_새_계약_기준이다() throws Exception {
    long id = createJeonse(property, today().minusDays(5), today().plusYears(1));
    assertThat(followupService.createDue()).isZero();
    jdbcTemplate.update(
        "UPDATE contract SET lease_end_date = ? WHERE id = ?",
        Date.valueOf(today().plusDays(80)),
        id);
    assertThat(followupService.createDue()).isEqualTo(1);
    assertThat(followups(id)).isEqualTo(1);
    assertThat(taskDue("CONTRACT_MATURITY", id)).isEqualTo(today().plusDays(80));
    assertThat(followupService.createDue()).isZero();
    assertThat(followups(id)).isEqualTo(1);

    long renewed =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            "/api/contracts/" + id + "/renew",
                            jeonse(
                                ownerMembership,
                                today(),
                                today().plusDays(81),
                                today().plusYears(2),
                                ""),
                            ownerCookie))
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("contractId")
            .longValue();
    assertThat(followups(renewed)).isZero();
    assertThat(followups(id)).isEqualTo(1);
  }

  @Test
  void 후속업무_제출은_계약_버전을_검사하고_다시_보내면_그대로_돌려준다() throws Exception {
    long id = createJeonse(property, today().minusDays(5), today().plusDays(40));
    long taskId = longValue("SELECT id FROM contract_followup_task WHERE contract_id = ?", id);
    String url = "/api/contract-followup-tasks/" + taskId;
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"submitted\":true}", ownerCookie, null)),
        "If-Match",
        "REQUIRED");
    long version = contractVersion(id);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"submitted\":true}", ownerCookie, version + 5)),
        ErrorCode.VERSION_CONFLICT);
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"submitted\":false}", ownerCookie, version)),
        "submitted",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"submitted\":true,\"x\":1}", ownerCookie, version)),
        "x",
        "NOT_ALLOWED");
    JsonNode done =
        AuthFlowSupport.json(
            mockMvc
                .perform(jsonPatchAs(url, "{\"submitted\":true}", ownerCookie, version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.version").value(version + 1))
                .andReturn());
    String submittedAt = done.get("submittedAt").asString();
    mockMvc
        .perform(jsonPatchAs(url, "{\"submitted\":true}", ownerCookie, version))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.submittedAt").value(submittedAt))
        .andExpect(jsonPath("$.version").value(version + 1));
    assertThat(contractVersion(id)).isEqualTo(version + 1);
    mockMvc
        .perform(authedGet("/api/contracts/" + id + "/followup-tasks", ownerCookie))
        .andExpect(jsonPath("$.items[0].submitted").value(true));
  }

  @Test
  void 계약_정정과_보수_정정_뒤_저장된_지난_달_결산의_보수_지표를_다시_계산한다() throws Exception {
    YearMonth lastMonth = YearMonth.now(KST).minusMonths(1);
    jdbcTemplate.update(
        "UPDATE office SET created_at = ? WHERE id = ?",
        Timestamp.from(lastMonth.minusMonths(2).atDay(1).atStartOfDay(KST).toInstant()),
        officeId);
    LocalDate signed = lastMonth.atDay(5);
    long id =
        create(
                property,
                jeonse(
                    ownerMembership,
                    signed,
                    lastMonth.atDay(20),
                    lastMonth.atDay(20).plusYears(2),
                    ""),
                ownerCookie)
            .get("contractId")
            .longValue();
    long landlord =
        getJson("/api/contracts/" + id, ownerCookie)
            .get("sellerLandlordParties")
            .get(0)
            .get("partyId")
            .longValue();
    long feeId =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            "/api/contracts/" + id + "/fees",
                            "{\"payerType\":\"SELLER_LANDLORD\",\"payerPartyId\":"
                                + landlord
                                + ",\"agreedFeeAmount\":1000000,\"vatIncluded\":false,"
                                + "\"supplyAmount\":1000000,\"taxAmount\":0,"
                                + "\"totalAmount\":1000000,\"billedAt\":\""
                                + signed
                                + "T00:00:00+09:00\"}",
                            ownerCookie))
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("feeId")
            .longValue();
    String receipt =
        "{\"transactionType\":\"RECEIPT\",\"amount\":300000,\"actualDate\":\""
            + lastMonth.atDay(25)
            + "\"}";
    long receiptId =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            "/api/contract-fees/" + feeId + "/transactions", receipt, ownerCookie))
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("transactionId")
            .longValue();

    getJson("/api/settlements/" + lastMonth, ownerCookie);
    assertSettlement(lastMonth, 1_000_000, 300_000, 700_000, 1);

    ObjectNode moved = correctionOf(id);
    moved.put("balanceDueDate", today().toString());
    correct(id, moved);
    assertSettlement(lastMonth, 0, 300_000, 700_000, 2);

    long initial = longValue("SELECT id FROM contract_fee_revision WHERE fee_id = ?", feeId);
    mockMvc
        .perform(
            jsonPostAs(
                "/api/contract-fees/" + feeId + "/revisions",
                "{\"revisionType\":\"CORRECTED\",\"effectiveDate\":\""
                    + signed
                    + "\",\"agreedFeeAmount\":800000,\"supplyAmount\":800000,\"taxAmount\":0,"
                    + "\"totalAmount\":800000,\"reason\":\"약정 금액 오입력\","
                    + "\"supersedesRevisionId\":"
                    + initial
                    + "}",
                ownerCookie))
        .andExpect(status().isCreated());
    assertSettlement(lastMonth, 0, 300_000, 500_000, 3);

    mockMvc
        .perform(
            jsonPostAs(
                "/api/contract-fees/" + feeId + "/transactions",
                receipt
                    .replace("300000", "200000")
                    .replace(
                        "}",
                        ",\"supersedesTransactionId\":"
                            + receiptId
                            + ",\"correctionReason\":\"수납액 오입력\"}"),
                ownerCookie))
        .andExpect(status().isCreated());
    assertSettlement(lastMonth, 0, 200_000, 600_000, 4);
    mockMvc
        .perform(authedGet("/api/settlements/" + lastMonth, ownerCookie))
        .andExpect(jsonPath("$.outstandingAmount").value(600_000));
  }

  private void assertSettlement(
      YearMonth month, long agreed, long received, long outstanding, long version) {
    var row =
        jdbcTemplate.queryForMap(
            "SELECT agreed_fee_total, actual_fee_received_total, outstanding_amount, version FROM"
                + " monthly_settlement WHERE office_id = ? AND year_month = ?",
            officeId,
            Date.valueOf(month.atDay(1)));
    assertThat(((Number) row.get("agreed_fee_total")).longValue()).isEqualTo(agreed);
    assertThat(((Number) row.get("actual_fee_received_total")).longValue()).isEqualTo(received);
    assertThat(((Number) row.get("outstanding_amount")).longValue()).isEqualTo(outstanding);
    assertThat(((Number) row.get("version")).longValue()).isEqualTo(version);
  }
}
