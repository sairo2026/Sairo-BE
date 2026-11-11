package com.sairo.be.domain.contract.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ContractRecordIntegrationTest extends ContractIntegrationTestBase {

  private static final byte[] PDF = "%PDF-1.4 contract".getBytes(StandardCharsets.ISO_8859_1);

  private static RequestBuilder upload(
      String url, Cookie cookie, String filename, byte[] content, Map<String, String> params) {
    MockMultipartHttpServletRequestBuilder builder = multipart(url);
    if (content != null) {
      builder.file(new MockMultipartFile("file", filename, "application/pdf", content));
    }
    params.forEach(builder::param);
    if (cookie != null) {
      builder.cookie(cookie);
    }
    builder
        .cookie(new Cookie("XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE))
        .header("X-XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE);
    return builder;
  }

  private static Map<String, String> docParams(
      Object revisionId, String type, String written, String delivered, String reason) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("contractRevisionId", String.valueOf(revisionId));
    if (type != null) {
      params.put("documentType", type);
    }
    if (written != null) {
      params.put("writtenDate", written);
    }
    if (delivered != null) {
      params.put("deliveredDate", delivered);
    }
    if (reason != null) {
      params.put("datePendingReason", reason);
    }
    params.put("piiReviewed", "true");
    return params;
  }

  private static String feeBody(String payerType, long partyId, long supply, long tax) {
    return "{\"payerType\":\""
        + payerType
        + "\",\"payerPartyId\":"
        + partyId
        + ",\"agreedFeeAmount\":"
        + (supply + tax)
        + ",\"vatIncluded\":"
        + (tax > 0)
        + ",\"supplyAmount\":"
        + supply
        + ",\"taxAmount\":"
        + tax
        + ",\"totalAmount\":"
        + (supply + tax)
        + ",\"billedAt\":\""
        + today()
        + "T00:00:00+09:00\"}";
  }

  private static String revisionBody(
      String type, String effective, long supply, long tax, Long supersedes) {
    return "{\"revisionType\":\""
        + type
        + "\",\"effectiveDate\":\""
        + effective
        + "\",\"agreedFeeAmount\":"
        + (supply + tax)
        + ",\"supplyAmount\":"
        + supply
        + ",\"taxAmount\":"
        + tax
        + ",\"totalAmount\":"
        + (supply + tax)
        + ",\"reason\":\"협의 조정\""
        + (supersedes == null ? "" : ",\"supersedesRevisionId\":" + supersedes)
        + "}";
  }

  private static String txBody(String type, long amount, String extra) {
    return "{\"transactionType\":\""
        + type
        + "\",\"amount\":"
        + amount
        + ",\"actualDate\":\""
        + today()
        + "\""
        + extra
        + "}";
  }

  private JsonNode postOk(String url, String body) throws Exception {
    return AuthFlowSupport.json(
        mockMvc
            .perform(jsonPostAs(url, body, ownerCookie))
            .andExpect(status().isCreated())
            .andReturn());
  }

  private long partyId(long contractId, String side, int index) throws Exception {
    return getJson("/api/contracts/" + contractId, ownerCookie)
        .get(side)
        .get(index)
        .get("partyId")
        .longValue();
  }

  private long revisionId(long contractId) throws Exception {
    return getJson("/api/contracts/" + contractId, ownerCookie)
        .get("currentRevisionId")
        .longValue();
  }

  private long draft() throws Exception {
    return AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(
                        "/api/properties/" + property + "/contracts/drafts",
                        "{\"dealType\":\"SALE\",\"managingMembershipId\":" + ownerMembership + "}",
                        ownerCookie))
                .andExpect(status().isCreated())
                .andReturn())
        .get("contractId")
        .longValue();
  }

  private Map<String, Function<Cookie, RequestBuilder>> endpoints(
      long contractId, long documentId, long feeId, long scheduleId, long taskId) {
    String base = "/api/contracts/" + contractId;
    Map<String, Function<Cookie, RequestBuilder>> map = new LinkedHashMap<>();
    map.put(
        "POST documents",
        c ->
            upload(
                base + "/documents",
                c,
                "a.pdf",
                PDF,
                docParams(1, "SALES_CONTRACT", "2026-01-02", "2026-01-02", null)));
    map.put(
        "POST revision",
        c ->
            upload(
                base + "/documents/" + documentId + "/revision",
                c,
                "a.pdf",
                PDF,
                docParams(1, null, "2026-01-02", "2026-01-02", null)));
    map.put("GET document", c -> authedGet(base + "/documents/" + documentId, c));
    map.put("POST fees", c -> jsonPostAs(base + "/fees", "{}", c));
    map.put("GET fees", c -> authedGet(base + "/fees", c));
    map.put(
        "POST fee revisions",
        c -> jsonPostAs("/api/contract-fees/" + feeId + "/revisions", "{}", c));
    map.put(
        "POST fee transactions",
        c -> jsonPostAs("/api/contract-fees/" + feeId + "/transactions", "{}", c));
    map.put("POST payment-schedules", c -> jsonPostAs(base + "/payment-schedules", "{}", c));
    map.put("GET payment-schedules", c -> authedGet(base + "/payment-schedules", c));
    map.put(
        "POST payment transactions",
        c -> jsonPostAs("/api/payment-schedules/" + scheduleId + "/transactions", "{}", c));
    map.put("GET followup-tasks", c -> authedGet(base + "/followup-tasks", c));
    map.put(
        "PATCH followup",
        c -> jsonPatchAs("/api/contract-followup-tasks/" + taskId, "{\"submitted\":true}", c, 1));
    return map;
  }

  @Test
  void 열두_개_엔드포인트는_권한과_사무소를_검사한다() throws Exception {
    long contractId = createJeonse(property, today().minusDays(5), today().plusDays(60));
    long documentId =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        upload(
                            "/api/contracts/" + contractId + "/documents",
                            ownerCookie,
                            "a.pdf",
                            PDF,
                            docParams(
                                revisionId(contractId),
                                "SALES_CONTRACT",
                                today().toString(),
                                today().toString(),
                                null)))
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("documentId")
            .longValue();
    long landlord = partyId(contractId, "sellerLandlordParties", 0);
    long feeId =
        postOk(
                "/api/contracts/" + contractId + "/fees",
                feeBody("SELLER_LANDLORD", landlord, 1_000_000, 100_000))
            .get("feeId")
            .longValue();
    long scheduleId =
        postOk(
                "/api/contracts/" + contractId + "/payment-schedules",
                "{\"installmentType\":\"DEPOSIT\",\"sequenceNo\":1,\"scheduledDate\":\""
                    + today()
                    + "\",\"scheduledAmount\":30000000}")
            .get("scheduleId")
            .longValue();
    long taskId =
        longValue("SELECT id FROM contract_followup_task WHERE contract_id = ?", contractId);
    var map = endpoints(contractId, documentId, feeId, scheduleId, taskId);
    assertThat(map).hasSize(12);

    Cookie plain = kakaoSessionFor(saveEmailUser("record-plain@example.com", "010-4545-3434"));
    Cookie deputy = cookieOf(staffIn(owner.office(), OfficeMembershipRole.DEPUTY));
    TestData.Staff other = newStaff(OfficeMembershipRole.OWNER);
    Cookie otherCookie = cookieOf(other);
    for (var entry : map.entrySet()) {
      assertError(mockMvc.perform(entry.getValue().apply(null)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(entry.getValue().apply(plain)), ErrorCode.FORBIDDEN);
      assertError(mockMvc.perform(entry.getValue().apply(deputy)), ErrorCode.FORBIDDEN);
      assertError(
          mockMvc.perform(entry.getValue().apply(otherCookie)), ErrorCode.RESOURCE_NOT_FOUND);
    }
    Cookie agent = cookieOf(staffIn(owner.office(), OfficeMembershipRole.AGENT));
    mockMvc
        .perform(authedGet("/api/contracts/" + contractId + "/documents/" + documentId, agent))
        .andExpect(status().isOk());
    assertThat(longValue("SELECT count(*) FROM contract_document")).isEqualTo(1);
    assertThat(longValue("SELECT count(*) FROM contract_fee")).isEqualTo(1);
    assertThat(longValue("SELECT count(*) FROM contract_payment_schedule")).isEqualTo(1);
  }

  @Test
  void 서류는_체결_뒤_최신_버전에만_올리고_보존_종료일과_정정본_버전을_남긴다() throws Exception {
    long draftId = draft();
    String draftUrl = "/api/contracts/" + draftId + "/documents";
    assertError(
        mockMvc.perform(
            upload(
                draftUrl,
                ownerCookie,
                "a.pdf",
                PDF,
                docParams(1, "SALES_CONTRACT", "2026-01-02", "2026-01-02", null))),
        ErrorCode.CONTRACT_REVISION_CONFLICT);

    long id =
        create(property, sale(ownerMembership, today().minusDays(3), ""), ownerCookie)
            .get("contractId")
            .longValue();
    String url = "/api/contracts/" + id + "/documents";
    long revision = revisionId(id);
    assertError(
        mockMvc.perform(
            upload(
                url,
                ownerCookie,
                "a.pdf",
                PDF,
                docParams(revision + 999, "SALES_CONTRACT", "2024-02-29", "2024-02-28", null))),
        ErrorCode.CONTRACT_REVISION_CONFLICT);
    Map<String, String> notReviewed =
        docParams(revision, "SALES_CONTRACT", "2024-02-29", "2024-02-28", null);
    notReviewed.put("piiReviewed", "false");
    assertFieldError(
        mockMvc.perform(upload(url, ownerCookie, "a.pdf", PDF, notReviewed)),
        "piiReviewed",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            upload(
                url,
                ownerCookie,
                "a.pdf",
                PDF,
                docParams(revision, "SALES_CONTRACT", "2024-02-29", null, null))),
        "datePendingReason",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            upload(
                url,
                ownerCookie,
                "a.txt",
                "hello".getBytes(StandardCharsets.UTF_8),
                docParams(revision, "SALES_CONTRACT", "2024-02-29", "2024-02-28", null))),
        "file",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            upload(
                url,
                ownerCookie,
                "a.pdf",
                null,
                docParams(revision, "SALES_CONTRACT", "2024-02-29", "2024-02-28", null))),
        "file",
        "REQUIRED");

    long version = contractVersion(id);
    JsonNode first =
        AuthFlowSupport.json(
            mockMvc
                .perform(
                    upload(
                        url,
                        ownerCookie,
                        "a.pdf",
                        PDF,
                        docParams(revision, "SALES_CONTRACT", "2024-02-29", "2024-02-28", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.retentionYears").value(5))
                .andExpect(jsonPath("$.retentionEndDate").value("2029-02-28"))
                .andExpect(jsonPath("$.contractRevisionId").value(revision))
                .andExpect(jsonPath("$.notice").doesNotExist())
                .andExpect(jsonPath("$.version").value(version + 1))
                .andReturn());
    long firstId = first.get("documentId").longValue();
    assertError(
        mockMvc.perform(
            upload(
                url,
                ownerCookie,
                "b.pdf",
                PDF,
                docParams(revision, "SALES_CONTRACT", "2024-03-01", "2024-03-01", null))),
        ErrorCode.DOCUMENT_VERSION_CONFLICT);

    Map<String, String> masked =
        docParams(revision, "DISCLOSURE_STATEMENT", "2024-03-01", null, "교부일 확인 중");
    masked.put("piiMaskingConfirmed", "true");
    mockMvc
        .perform(upload(url, ownerCookie, "c.png", pngBytes(), masked))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.retentionYears").value(3))
        .andExpect(jsonPath("$.retentionEndDate").doesNotExist())
        .andExpect(jsonPath("$.datePendingReason").value("교부일 확인 중"))
        .andExpect(jsonPath("$.piiMaskingConfirmed").value(true))
        .andExpect(jsonPath("$.notice").isString());

    String reviseUrl = url + "/" + firstId + "/revision";
    assertFieldError(
        mockMvc.perform(
            upload(
                reviseUrl,
                ownerCookie,
                "d.pdf",
                PDF,
                docParams(revision, "DISCLOSURE_STATEMENT", "2025-01-10", "2025-01-11", null))),
        "documentType",
        "NOT_ALLOWED");
    mockMvc
        .perform(
            upload(
                reviseUrl,
                ownerCookie,
                "d.pdf",
                PDF,
                docParams(revision, null, "2025-01-10", "2025-01-11", null)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.documentType").value("SALES_CONTRACT"))
        .andExpect(jsonPath("$.versionNo").value(2))
        .andExpect(jsonPath("$.supersedesDocumentId").value(firstId))
        .andExpect(jsonPath("$.retentionEndDate").value("2030-01-11"));
    assertError(
        mockMvc.perform(
            upload(
                reviseUrl,
                ownerCookie,
                "e.pdf",
                PDF,
                docParams(revision, null, "2025-01-10", "2025-01-11", null))),
        ErrorCode.DOCUMENT_VERSION_CONFLICT);
    assertThat(text("SELECT retention_end_date::text FROM contract_document WHERE id = ?", firstId))
        .isEqualTo("2029-02-28");

    mockMvc
        .perform(
            jsonPostAs(
                    "/api/contracts/" + id + "/corrections",
                    correctionOf(id).put("salePrice", 510_000_000L).toString(),
                    ownerCookie)
                .header("If-Match", contractVersion(id)))
        .andExpect(status().isCreated());
    assertError(
        mockMvc.perform(
            upload(
                reviseUrl,
                ownerCookie,
                "f.pdf",
                PDF,
                docParams(revision, null, "2025-01-10", "2025-01-11", null))),
        ErrorCode.CONTRACT_REVISION_CONFLICT);
    assertThat(
            longValue(
                "SELECT count(*) FROM contract_document WHERE contract_id = ? AND"
                    + " contract_revision_id = ?",
                id,
                revision))
        .isEqualTo(3);

    mockMvc
        .perform(authedGet(url + "/" + firstId, ownerCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentId").value(firstId))
        .andExpect(jsonPath("$.downloadUrl").isString())
        .andExpect(jsonPath("$.expiresAt").isString());
    assertError(
        mockMvc.perform(authedGet(url + "/999999", ownerCookie)), ErrorCode.RESOURCE_NOT_FOUND);
    assertThat(getJson("/api/contracts/" + id, ownerCookie).get("documents").size()).isEqualTo(3);
  }

  private static byte[] pngBytes() {
    return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
  }

  @Test
  void 중개보수는_체결_뒤_지급의무자별로_청구하고_조정_정정_수납_환급을_더하기만_한다() throws Exception {
    long draftId = draft();
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/contracts/" + draftId + "/fees",
                feeBody("SELLER_LANDLORD", 1, 1_000_000, 100_000),
                ownerCookie)),
        ErrorCode.CONTRACT_ACTION_NOT_ALLOWED);

    long id =
        create(property, sale(ownerMembership, today().minusDays(3), ""), ownerCookie)
            .get("contractId")
            .longValue();
    long seller = partyId(id, "sellerLandlordParties", 0);
    long buyer = partyId(id, "buyerTenantParties", 0);
    String feesUrl = "/api/contracts/" + id + "/fees";
    assertError(
        mockMvc.perform(
            jsonPostAs(
                feesUrl,
                feeBody("SELLER_LANDLORD", seller, 1_000_000, 100_000)
                    .replace("\"totalAmount\":1100000", "\"totalAmount\":1100001"),
                ownerCookie)),
        ErrorCode.FEE_AMOUNT_MISMATCH);
    assertError(
        mockMvc.perform(
            jsonPostAs(feesUrl, feeBody("BUYER_TENANT", seller, 1_000_000, 100_000), ownerCookie)),
        ErrorCode.INVALID_CONTRACT_PARTY);

    long version = contractVersion(id);
    JsonNode sellerFee = postOk(feesUrl, feeBody("SELLER_LANDLORD", seller, 1_000_000, 100_000));
    assertThat(sellerFee.get("version").longValue()).isEqualTo(version + 1);
    assertThat(sellerFee.get("totalAmount").longValue()).isEqualTo(1_100_000);
    long sellerFeeId = sellerFee.get("feeId").longValue();
    long buyerFeeId =
        postOk(feesUrl, feeBody("BUYER_TENANT", buyer, 500_000, 0)).get("feeId").longValue();
    long initial =
        longValue(
            "SELECT id FROM contract_fee_revision WHERE fee_id = ? AND revision_type = 'INITIAL'",
            sellerFeeId);
    long buyerInitial =
        longValue("SELECT id FROM contract_fee_revision WHERE fee_id = ?", buyerFeeId);

    String revUrl = "/api/contract-fees/" + sellerFeeId + "/revisions";
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                revUrl,
                revisionBody(
                    "ADJUSTED",
                    today().withDayOfMonth(1).minusDays(1).toString(),
                    900_000,
                    90_000,
                    null),
                ownerCookie)),
        "effectiveDate",
        "OUT_OF_RANGE");
    long adjusted =
        postOk(revUrl, revisionBody("ADJUSTED", today().toString(), 900_000, 90_000, null))
            .get("revisionId")
            .longValue();
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                revUrl,
                revisionBody("CORRECTED", today().toString(), 800_000, 80_000, null),
                ownerCookie)),
        "supersedesRevisionId",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                revUrl,
                revisionBody("CORRECTED", today().toString(), 800_000, 80_000, buyerInitial),
                ownerCookie)),
        "supersedesRevisionId",
        "INVALID_VALUE");
    postOk(revUrl, revisionBody("CORRECTED", today().toString(), 800_000, 80_000, adjusted));
    assertError(
        mockMvc.perform(
            jsonPostAs(
                revUrl,
                revisionBody("CORRECTED", today().toString(), 700_000, 70_000, adjusted),
                ownerCookie)),
        ErrorCode.INVALID_TRANSITION);

    String txUrl = "/api/contract-fees/" + sellerFeeId + "/transactions";
    long receipt = postOk(txUrl, txBody("RECEIPT", 500_000, "")).get("transactionId").longValue();
    assertFieldError(
        mockMvc.perform(jsonPostAs(txUrl, txBody("REFUND", 600_000, ""), ownerCookie)),
        "amount",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(jsonPostAs(txUrl, txBody("RECEIPT", 0, ""), ownerCookie)),
        "amount",
        "OUT_OF_RANGE");
    postOk(txUrl, txBody("REFUND", 100_000, ""));
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                txUrl,
                txBody("RECEIPT", 400_000, ",\"supersedesTransactionId\":" + receipt),
                ownerCookie)),
        "correctionReason",
        "REQUIRED");
    String correction =
        ",\"supersedesTransactionId\":" + receipt + ",\"correctionReason\":\"금액 오입력\"";
    postOk(txUrl, txBody("RECEIPT", 400_000, correction));
    assertError(
        mockMvc.perform(jsonPostAs(txUrl, txBody("RECEIPT", 450_000, correction), ownerCookie)),
        ErrorCode.INVALID_TRANSITION);
    String buyerTx = "/api/contract-fees/" + buyerFeeId + "/transactions";
    assertFieldError(
        mockMvc.perform(jsonPostAs(buyerTx, txBody("RECEIPT", 1, correction), ownerCookie)),
        "supersedesTransactionId",
        "INVALID_VALUE");
    postOk(buyerTx, txBody("RECEIPT", 700_000, ""));

    JsonNode list = getJson(feesUrl, ownerCookie);
    JsonNode first = list.get("items").get(0);
    assertThat(first.get("feeId").longValue()).isEqualTo(sellerFeeId);
    assertThat(first.get("totalAmount").longValue()).isEqualTo(880_000);
    assertThat(first.get("agreedFeeAmount").longValue()).isEqualTo(880_000);
    assertThat(first.get("netReceivedAmount").longValue()).isEqualTo(300_000);
    assertThat(first.get("revisions").size()).isEqualTo(3);
    assertThat(first.get("revisions").get(0).get("revisionId").longValue()).isEqualTo(initial);
    assertThat(first.get("revisions").get(1).get("isSuperseded").booleanValue()).isTrue();
    assertThat(first.get("transactions").size()).isEqualTo(3);
    assertThat(first.get("billedAt").asString()).isEqualTo(today() + "T00:00:00+09:00");
    JsonNode payers = list.get("payers");
    assertThat(payers.size()).isEqualTo(2);
    assertThat(payers.get(0).get("outstandingAmount").longValue()).isEqualTo(580_000);
    assertThat(payers.get(0).get("overcollectedAmount").longValue()).isZero();
    assertThat(payers.get(1).get("outstandingAmount").longValue()).isZero();
    assertThat(payers.get(1).get("overcollectedAmount").longValue()).isEqualTo(200_000);
    assertThat(longValue("SELECT total_amount FROM contract_fee WHERE id = ?", sellerFeeId))
        .isEqualTo(880_000);
    assertThat(list.get("items").get(1).get("version").longValue()).isEqualTo(contractVersion(id));

    var body = correctionOf(id);
    ((tools.jackson.databind.node.ArrayNode) body.get("sellerLandlordParties")).remove(0);
    assertError(
        mockMvc.perform(
            jsonPostAs("/api/contracts/" + id + "/corrections", body.toString(), ownerCookie)
                .header("If-Match", contractVersion(id))),
        ErrorCode.INVALID_CONTRACT_PARTY);
  }

  @Test
  void 계약대금은_체결_뒤_예정을_등록하고_합계_불일치는_경고와_사유로_남기며_실지급은_대체로_정정한다() throws Exception {
    long draftId = draft();
    String schedule =
        "{\"installmentType\":\"DEPOSIT\",\"sequenceNo\":1,\"scheduledDate\":\""
            + today()
            + "\",\"scheduledAmount\":50000000}";
    assertError(
        mockMvc.perform(
            jsonPostAs("/api/contracts/" + draftId + "/payment-schedules", schedule, ownerCookie)),
        ErrorCode.CONTRACT_ACTION_NOT_ALLOWED);

    long id =
        create(property, sale(ownerMembership, today().minusDays(3), ""), ownerCookie)
            .get("contractId")
            .longValue();
    String url = "/api/contracts/" + id + "/payment-schedules";
    JsonNode deposit = postOk(url, schedule);
    assertThat(deposit.get("warning").asString()).isEqualTo("AMOUNT_MISMATCH");
    long depositId = deposit.get("scheduleId").longValue();
    assertError(
        mockMvc.perform(jsonPostAs(url, schedule, ownerCookie)),
        ErrorCode.CONTRACT_ACTION_NOT_ALLOWED);
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                url,
                schedule
                    .replace("DEPOSIT", "INTERIM")
                    .replace("}", ",\"amountMismatchReason\":\"  \"}"),
                ownerCookie)),
        "amountMismatchReason",
        "REQUIRED");
    long interimId =
        postOk(
                url,
                schedule
                    .replace("DEPOSIT", "INTERIM")
                    .replace("50000000", "200000000")
                    .replace("}", ",\"amountMismatchReason\":\"중도금 조정 협의\"}"))
            .get("scheduleId")
            .longValue();
    assertThat(text("SELECT payment_amount_mismatch_reason FROM contract WHERE id = ?", id))
        .isEqualTo("중도금 조정 협의");
    mockMvc
        .perform(authedGet(url, ownerCookie))
        .andExpect(jsonPath("$.warning").value("AMOUNT_MISMATCH"))
        .andExpect(jsonPath("$.amountMismatchReason").value("중도금 조정 협의"))
        .andExpect(jsonPath("$.scheduledTotal").value(250_000_000));
    JsonNode balance =
        postOk(url, schedule.replace("DEPOSIT", "BALANCE").replace("50000000", "250000000"));
    assertThat(balance.get("warning").isNull()).isTrue();
    assertThat(text("SELECT payment_amount_mismatch_reason FROM contract WHERE id = ?", id))
        .isNull();

    String payUrl = "/api/payment-schedules/" + depositId + "/transactions";
    String pay20 = "{\"actualAmount\":20000000,\"actualDate\":\"" + today() + "\"}";
    postOk(payUrl, pay20);
    long second =
        postOk(payUrl, pay20.replace("20000000", "30000000")).get("transactionId").longValue();
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                payUrl,
                pay20.replace(today().toString(), today().plusDays(1).toString()),
                ownerCookie)),
        "actualDate",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                payUrl,
                pay20.replace("}", ",\"supersedesTransactionId\":" + second + "}"),
                ownerCookie)),
        "correctionReason",
        "REQUIRED");
    String fix =
        pay20
            .replace("20000000", "25000000")
            .replace(
                "}",
                ",\"supersedesTransactionId\":" + second + ",\"correctionReason\":\"금액 오입력\"}");
    JsonNode corrected = postOk(payUrl, fix);
    assertThat(corrected.get("confirmedByMembershipId").longValue()).isEqualTo(ownerMembership);
    assertError(
        mockMvc.perform(jsonPostAs(payUrl, fix, ownerCookie)), ErrorCode.INVALID_TRANSITION);
    assertFieldError(
        mockMvc.perform(
            jsonPostAs("/api/payment-schedules/" + interimId + "/transactions", fix, ownerCookie)),
        "supersedesTransactionId",
        "INVALID_VALUE");

    JsonNode list = getJson(url, ownerCookie);
    assertThat(list.get("scheduledTotal").longValue()).isEqualTo(500_000_000);
    assertThat(list.get("contractAmountTotal").longValue()).isEqualTo(500_000_000);
    assertThat(list.get("warning").isNull()).isTrue();
    JsonNode first = list.get("items").get(0);
    assertThat(first.get("installmentType").asString()).isEqualTo("DEPOSIT");
    assertThat(first.get("actualPaidTotal").longValue()).isEqualTo(45_000_000);
    assertThat(first.get("paymentStatus").asString()).isEqualTo("PARTIAL");
    assertThat(first.get("transactions").size()).isEqualTo(3);
    assertThat(first.get("transactions").get(1).get("isSuperseded").booleanValue()).isTrue();
    assertThat(first.get("transactions").get(0).get("confirmedByMemberName").isString()).isTrue();
    assertThat(list.get("items").get(1).get("paymentStatus").asString()).isEqualTo("UNPAID");
    postOk(payUrl, pay20.replace("20000000", "5000000"));
    mockMvc
        .perform(authedGet(url, ownerCookie))
        .andExpect(jsonPath("$.items[0].paymentStatus").value("PAID"));
  }
}
