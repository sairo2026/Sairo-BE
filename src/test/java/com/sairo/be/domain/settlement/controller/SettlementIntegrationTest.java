package com.sairo.be.domain.settlement.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitOutcomeTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class SettlementIntegrationTest extends VisitOutcomeTestBase {

  private static YearMonth thisMonth() {
    return YearMonth.now(KST);
  }

  private static Instant at(YearMonth month, int day) {
    return month.atDay(day).atTime(LocalTime.NOON).atZone(KST).toInstant();
  }

  private void backdateOffice(long officeId, int months) {
    jdbcTemplate.update(
        "UPDATE office SET created_at = ? WHERE id = ?",
        Timestamp.from(at(thisMonth().minusMonths(months), 1)),
        officeId);
  }

  private long contract(
      long officeId,
      long propertyId,
      long membershipId,
      String dealType,
      LocalDate balanceDueDate,
      LocalDate leaseEndDate) {
    boolean lease = !"SALE".equals(dealType);
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO contract (office_id, property_id, deal_type, managing_membership_id,"
                + " seller_landlord_name, buyer_tenant_name, signed_date, sale_price,"
                + " deposit_amount, monthly_rent_amount, balance_due_date,"
                + " balance_due_date_pending_reason, lease_start_date, lease_end_date,"
                + " contract_status, lease_period_status) VALUES (?, ?, ?, ?, '김매도', '이매수', ?,"
                + " ?, ?, NULL, ?, ?, ?, ?, 'SIGNED', ?) RETURNING id",
            Long.class,
            officeId,
            propertyId,
            dealType,
            membershipId,
            Date.valueOf(LocalDate.now(KST)),
            lease ? null : 500_000_000L,
            lease ? 300_000_000L : null,
            balanceDueDate == null ? null : Date.valueOf(balanceDueDate),
            balanceDueDate == null ? "잔금일 협의 중" : null,
            lease ? Date.valueOf(leaseEndDate.minusYears(2)) : null,
            lease ? Date.valueOf(leaseEndDate) : null,
            lease ? "IN_PROGRESS" : null);
    statusHistory(id, null, "SIGNED", Instant.now().minusSeconds(60));
    return id == null ? -1 : id;
  }

  private void statusHistory(long contractId, String from, String to, Instant effectiveAt) {
    jdbcTemplate.update(
        "INSERT INTO contract_status_history (contract_id, track, from_status, to_status,"
            + " effective_at) VALUES (?, 'CONTRACT', ?, ?, ?)",
        contractId,
        from,
        to,
        Timestamp.from(effectiveAt));
  }

  private long fee(
      long contractId, String side, long membershipId, long agreed, long total, long received) {
    Long party =
        jdbcTemplate.queryForObject(
            "INSERT INTO contract_party (contract_id, side, sequence_no, name) VALUES (?, ?, 1,"
                + " ?) RETURNING id",
            Long.class,
            contractId,
            side,
            side.equals("BUYER_TENANT") ? "이매수" : "김매도");
    long tax = total - agreed;
    Long feeId =
        jdbcTemplate.queryForObject(
            "INSERT INTO contract_fee (contract_id, payer_type, payer_party_id,"
                + " agreed_fee_amount, vat_included, supply_amount, tax_amount, total_amount,"
                + " billed_at) VALUES (?, ?, ?, ?, true, ?, ?, ?, ?) RETURNING id",
            Long.class,
            contractId,
            side,
            party,
            agreed,
            agreed,
            tax,
            total,
            Date.valueOf(LocalDate.now(KST)));
    jdbcTemplate.update(
        "INSERT INTO contract_fee_revision (fee_id, revision_type, effective_date,"
            + " agreed_fee_amount, supply_amount, tax_amount, total_amount,"
            + " recorded_by_membership_id) VALUES (?, 'INITIAL', ?, ?, ?, ?, ?, ?)",
        feeId,
        Date.valueOf(LocalDate.now(KST)),
        agreed,
        agreed,
        tax,
        total,
        membershipId);
    if (received > 0) {
      jdbcTemplate.update(
          "INSERT INTO contract_fee_transaction (fee_id, transaction_type, amount, actual_date,"
              + " recorded_by_membership_id) VALUES (?, 'RECEIPT', ?, ?, ?)",
          feeId,
          received,
          Date.valueOf(LocalDate.now(KST)),
          membershipId);
    }
    return feeId == null ? -1 : feeId;
  }

  private JsonNode getJson(String url, Cookie cookie) throws Exception {
    return AuthFlowSupport.json(
        mockMvc.perform(authedGet(url, cookie)).andExpect(status().isOk()).andReturn());
  }

  private long lifecycleEvent(String column, long id, String type) {
    return count(
        "SELECT id FROM entity_lifecycle_event WHERE "
            + column
            + " = ? AND event_type = ? ORDER BY id DESC LIMIT 1",
        id,
        type);
  }

  private static String correctionBody(Instant effectiveAt) {
    return "{\"effectiveAt\":\""
        + effectiveAt.atZone(KST).toOffsetDateTime()
        + "\",\"correctionReason\":\"등록일 오입력\"}";
  }

  private Map<String, Function<Cookie, MockHttpServletRequestBuilder>> endpoints(long eventId) {
    String month = thisMonth().toString();
    Map<String, Function<Cookie, MockHttpServletRequestBuilder>> map = new LinkedHashMap<>();
    map.put("GET /api/settlements", c -> authedGet("/api/settlements", c));
    map.put("GET /api/settlements/{ym}", c -> authedGet("/api/settlements/" + month, c));
    map.put(
        "GET /api/settlements/{ym}/details",
        c -> authedGet("/api/settlements/" + month + "/details?metric=propertyCountTotal", c));
    map.put(
        "GET /api/settlements/{ym}/memo", c -> authedGet("/api/settlements/" + month + "/memo", c));
    map.put(
        "PUT /api/settlements/{ym}/memo",
        c -> jsonPutAs("/api/settlements/" + month + "/memo", "{\"content\":\"메모\"}", c, 0));
    map.put(
        "GET /api/settlements/{ym}/export",
        c -> authedGet("/api/settlements/" + month + "/export", c));
    map.put(
        "POST /api/entity-lifecycle-events/{id}/corrections",
        c ->
            jsonPostAs(
                "/api/entity-lifecycle-events/" + eventId + "/corrections",
                correctionBody(Instant.now().minusSeconds(3600)),
                c));
    return map;
  }

  @Test
  void 로그인이_없으면_401이고_소속이_없거나_중개보조원이면_403이다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    long property = createProperty(cookieOf(owner), "권한 매물", categoryIdOf("APARTMENT"));
    long eventId = lifecycleEvent("property_id", property, "CREATED");
    Cookie plain = kakaoSessionFor(saveEmailUser("settle-plain@example.com", "010-4545-6767"));
    Cookie deputy = cookieOf(staffIn(owner.office(), OfficeMembershipRole.DEPUTY));
    for (var entry : endpoints(eventId).entrySet()) {
      assertError(mockMvc.perform(entry.getValue().apply(null)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(entry.getValue().apply(plain)), ErrorCode.FORBIDDEN);
      assertError(mockMvc.perform(entry.getValue().apply(deputy)), ErrorCode.FORBIDDEN);
    }
    assertThat(count("SELECT count(*) FROM monthly_memo")).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM entity_lifecycle_event WHERE supersedes_event_id IS NOT NULL"))
        .isZero();
  }

  @Test
  void 현재_달_결산은_사건으로_계산하고_상세와_내보내기가_같은_조건이며_다른_사무소는_보이지_않는다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    TestData.Staff agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    Cookie cookie = cookieOf(agent);
    long officeId = owner.office().getId();
    long member = owner.membership().getId();
    long p1 = createProperty(cookie, "결산 매물 1", categoryIdOf("APARTMENT"));
    long p2 = createProperty(cookie, "결산 매물 2", categoryIdOf("APARTMENT"));
    long c1 = createCustomer(cookie, "결산 고객", "010-2020-3030", member);
    long c2 = createCustomer(cookie, "병합 고객", "010-2020-4040", member);
    mergeRow(c1, c2, member);
    createConsultationRow(c1, member, "FIRST_INQUIRY");

    LocalDate lastDay = thisMonth().atEndOfMonth();
    long leased = contract(officeId, p1, member, "JEONSE", LocalDate.now(KST), lastDay);
    fee(leased, "BUYER_TENANT", member, 1_000_000, 1_100_000, 500_000);
    fee(leased, "SELLER_LANDLORD", member, 200_000, 200_000, 300_000);
    long undated = contract(officeId, p1, member, "SALE", null, null);
    long cancelled = contract(officeId, p2, member, "SALE", LocalDate.now(KST), null);
    statusHistory(cancelled, "SIGNED", "CANCELLED", Instant.now().minusSeconds(30));
    long duplicate = contract(officeId, p2, member, "SALE", LocalDate.now(KST), null);
    jdbcTemplate.update(
        "UPDATE contract SET duplicate_of_contract_id = ?, duplicate_excluded_at = now(),"
            + " duplicate_exclusion_reason = '중복', duplicate_excluded_by_membership_id = ? WHERE"
            + " id = ?",
        undated,
        member,
        duplicate);

    String month = thisMonth().toString();
    mockMvc
        .perform(authedGet("/api/settlements/" + month, cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.yearMonth").value(month))
        .andExpect(jsonPath("$.isFinalized").value(false))
        .andExpect(jsonPath("$.propertyCountTotal").value(2))
        .andExpect(jsonPath("$.propertyCountNew").value(2))
        .andExpect(jsonPath("$.propertyCountNewMigrated").value(0))
        .andExpect(jsonPath("$.customerCountTotal").value(1))
        .andExpect(jsonPath("$.customerCountNew").value(1))
        .andExpect(jsonPath("$.firstInquiryCount").value(1))
        .andExpect(jsonPath("$.contractSignedCount").value(2))
        .andExpect(jsonPath("$.contractSignedPropertyCount").value(1))
        .andExpect(jsonPath("$.leaseMaturityCount").value(1))
        .andExpect(jsonPath("$.leaseMaturityPropertyCount").value(1))
        .andExpect(jsonPath("$.agreedFeeTotal").value(1_200_000))
        .andExpect(jsonPath("$.agreedFeeUndatedCount").value(1))
        .andExpect(jsonPath("$.actualFeeReceivedTotal").value(800_000))
        .andExpect(jsonPath("$.outstandingAmount").value(600_000))
        .andExpect(jsonPath("$.overcollectedAmount").value(100_000))
        .andExpect(jsonPath("$.asOf").isNotEmpty());
    assertThat(count("SELECT count(*) FROM monthly_settlement")).isZero();

    mockMvc
        .perform(
            authedGet("/api/settlements/" + month + "/details?metric=customerCountTotal", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metric").value("customerCountTotal"))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.items[0].customerId").value(c1))
        .andExpect(jsonPath("$.items[0].customerName").value("결산 고객"))
        .andExpect(jsonPath("$.items[0].isMigrated").value(false))
        .andExpect(jsonPath("$.items[0].effectiveAt").isNotEmpty());
    mockMvc
        .perform(
            authedGet("/api/settlements/" + month + "/details?metric=contractSignedCount", cookie))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(
            jsonPath("$.items[*].contractId")
                .value(org.hamcrest.Matchers.containsInAnyOrder((int) leased, (int) undated)));
    mockMvc
        .perform(
            authedGet("/api/settlements/" + month + "/details?metric=outstandingAmount", cookie))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].payerType").value("BUYER_TENANT"))
        .andExpect(jsonPath("$.items[0].billedTotal").value(1_100_000))
        .andExpect(jsonPath("$.items[0].netReceivedTotal").value(500_000))
        .andExpect(jsonPath("$.items[0].balanceAmount").value(600_000));
    mockMvc
        .perform(
            authedGet(
                "/api/settlements/" + month + "/details?metric=agreedFeeUndatedCount", cookie))
        .andExpect(jsonPath("$.items[0].contractId").value(undated))
        .andExpect(jsonPath("$.items[0].balanceDueDatePendingReason").value("잔금일 협의 중"));
    mockMvc
        .perform(
            authedGet(
                "/api/settlements/" + month + "/details?metric=actualFeeReceivedTotal", cookie))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].transactionType").value("RECEIPT"))
        .andExpect(jsonPath("$.items[0].netAmount").doesNotExist());

    String csv =
        mockMvc
            .perform(authedGet("/api/settlements/" + month + "/export", cookie))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String[] lines = csv.split("\r\n");
    assertThat(lines).hasSize(2);
    assertThat(lines[0]).startsWith("yearMonth,isFinalized,propertyCountTotal,customerCountTotal");
    assertThat(lines[0])
        .endsWith("propertyCountNewMigrated,customerCountNewMigrated,agreedFeeUndatedCount,asOf");
    assertThat(lines[1]).startsWith(month + ",false,2,1,2,1,1,");

    mockMvc
        .perform(authedGet("/api/settlements", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].yearMonth").value(month))
        .andExpect(jsonPath("$.items[0].isFinalized").value(false))
        .andExpect(jsonPath("$.items[0].contractSignedCount").value(2));

    Cookie other = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    mockMvc
        .perform(authedGet("/api/settlements/" + month, other))
        .andExpect(jsonPath("$.propertyCountTotal").value(0))
        .andExpect(jsonPath("$.contractSignedCount").value(0))
        .andExpect(jsonPath("$.agreedFeeTotal").value(0));
    mockMvc
        .perform(
            authedGet("/api/settlements/" + month + "/details?metric=propertyCountTotal", other))
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void 결산_월과_지표_형식이_틀리거나_범위를_벗어나면_400이다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    String month = thisMonth().toString();
    for (String bad : new String[] {"2026-13", "202611", "abc"}) {
      assertFieldError(
          mockMvc.perform(authedGet("/api/settlements/" + bad, cookie)),
          "yearMonth",
          "INVALID_FORMAT");
    }
    assertFieldError(
        mockMvc.perform(authedGet("/api/settlements/" + thisMonth().plusMonths(1), cookie)),
        "yearMonth",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(
            authedGet("/api/settlements/" + thisMonth().minusMonths(1) + "/export", cookie)),
        "yearMonth",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(authedGet("/api/settlements/" + month + "/details", cookie)),
        "metric",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(authedGet("/api/settlements/" + month + "/details?metric=revenue", cookie)),
        "metric",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            authedGet(
                "/api/settlements/" + month + "/details?metric=propertyCountTotal&size=20",
                cookie)),
        "size",
        "NOT_ALLOWED");
  }

  @Test
  void 지난_달은_저장값을_만들고_방문_결과를_정정하면_그_달을_다시_계산한다() throws Exception {
    SelectionFixture s = confirmed();
    long officeId = s.base().owner().office().getId();
    backdateOffice(officeId, 3);
    long item = scheduledItem(s.coordinationId());
    recordResult(s, item, completedBody(5)).andExpect(status().isOk());

    YearMonth previous = thisMonth().minusMonths(1);
    Timestamp visited = Timestamp.from(at(previous, 10));
    jdbcTemplate.update(
        "UPDATE visit_schedule_item_result_history SET actual_visited_at = ?, effective_at = ?"
            + " WHERE schedule_item_id = ?",
        visited,
        visited,
        item);
    jdbcTemplate.update(
        "UPDATE visit_schedule_item SET actual_visited_at = ? WHERE id = ?", visited, item);
    Timestamp completedAt =
        jdbcTemplate.queryForObject(
            "SELECT effective_at FROM visit_coordination_status_history WHERE coordination_id = ?"
                + " AND to_status = 'VISIT_COMPLETED'",
            Timestamp.class,
            s.coordinationId());
    jdbcTemplate.update(
        "UPDATE visit_coordination_status_history SET effective_at = effective_at - (?::timestamptz"
            + " - ?::timestamptz) WHERE coordination_id = ?",
        completedAt,
        visited,
        s.coordinationId());

    JsonNode first = getJson("/api/settlements/" + previous, s.cookie());
    assertThat(first.get("isFinalized").booleanValue()).isTrue();
    assertThat(first.get("visitCompletedCount").longValue()).isEqualTo(1);
    assertThat(first.get("visitCompletedPropertyCount").longValue()).isEqualTo(1);
    assertThat(first.get("coordinationEndedCount").longValue()).isEqualTo(1);
    assertThat(first.get("coordinationVisitCompletedCount").longValue()).isEqualTo(1);
    assertThat(
            count(
                "SELECT visit_completed_count FROM monthly_settlement WHERE office_id = ?",
                officeId))
        .isEqualTo(1);
    JsonNode again = getJson("/api/settlements/" + previous, s.cookie());
    assertThat(again.get("asOf").asString()).isEqualTo(first.get("asOf").asString());
    assertThat(count("SELECT version FROM monthly_settlement WHERE office_id = ?", officeId))
        .isEqualTo(1);
    mockMvc
        .perform(
            authedGet(
                "/api/settlements/" + previous + "/details?metric=visitCompletedCount", s.cookie()))
        .andExpect(jsonPath("$.items[0].scheduleItemId").value(item))
        .andExpect(jsonPath("$.items[0].coordinationId").value(s.coordinationId()));

    recordResult(s, item, "{\"result\":\"NO_SHOW\",\"correctionReason\":\"방문하지 않았음\"}")
        .andExpect(status().isOk());

    assertThat(
            count(
                "SELECT visit_completed_count FROM monthly_settlement WHERE office_id = ?",
                officeId))
        .isZero();
    assertThat(count("SELECT version FROM monthly_settlement WHERE office_id = ?", officeId))
        .isEqualTo(2);
    JsonNode corrected = getJson("/api/settlements/" + previous, s.cookie());
    assertThat(corrected.get("visitCompletedCount").longValue()).isZero();
    assertThat(corrected.get("coordinationEndedCount").longValue()).isEqualTo(1);
    mockMvc
        .perform(authedGet("/api/settlements", s.cookie()))
        .andExpect(jsonPath("$.items.length()").value(4))
        .andExpect(jsonPath("$.items[0].isFinalized").value(false))
        .andExpect(jsonPath("$.items[1].yearMonth").value(previous.toString()))
        .andExpect(jsonPath("$.items[1].isFinalized").value(true));
  }

  @Test
  void 월간_메모는_버전_0으로_처음_쓰고_버전이_다르면_409이며_민감번호를_막는다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    String url = "/api/settlements/" + thisMonth() + "/memo";
    mockMvc
        .perform(authedGet(url, cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isEmpty())
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.updatedByMembershipId").isEmpty());

    assertFieldError(
        mockMvc.perform(jsonPutAs(url, "{\"content\":\"메모\"}", cookie, null)),
        "If-Match",
        "REQUIRED");
    mockMvc
        .perform(jsonPutAs(url, "{\"content\":\"이번 달은 계약 2건\"}", cookie, 0))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value("이번 달은 계약 2건"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.updatedByMembershipId").value(owner.membership().getId()))
        .andExpect(jsonPath("$.updatedByMemberName").value(owner.user().getName()))
        .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    assertError(
        mockMvc.perform(jsonPutAs(url, "{\"content\":\"동시 작성\"}", cookie, 0)),
        ErrorCode.VERSION_CONFLICT);
    mockMvc
        .perform(jsonPutAs(url, "{\"content\":\"\"}", cookie, 1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isEmpty())
        .andExpect(jsonPath("$.version").value(2));
    assertError(
        mockMvc.perform(jsonPutAs(url, "{\"content\":\"늦은 수정\"}", cookie, 1)),
        ErrorCode.VERSION_CONFLICT);
    assertError(
        mockMvc.perform(jsonPutAs(url, "{\"content\":\"주민번호 900101-1234567\"}", cookie, 2)),
        ErrorCode.SENSITIVE_DATA_DETECTED);
    assertFieldError(
        mockMvc.perform(jsonPutAs(url, "{\"content\":\"" + "가".repeat(2001) + "\"}", cookie, 2)),
        "content",
        "TOO_LONG");
    assertFieldError(
        mockMvc.perform(jsonPutAs(url, "{\"memo\":\"x\"}", cookie, 2)), "memo", "NOT_ALLOWED");

    Cookie other = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    mockMvc
        .perform(authedGet(url, other))
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.content").isEmpty());
  }

  @Test
  void 수명_사건_효력시각을_정정하면_정정_사건을_더하고_영향받은_달을_다시_계산한다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long officeId = owner.office().getId();
    backdateOffice(officeId, 2);
    long property = createProperty(cookie, "정정 매물", categoryIdOf("APARTMENT"));
    long created = lifecycleEvent("property_id", property, "CREATED");
    YearMonth previous = thisMonth().minusMonths(1);
    assertThat(
            getJson("/api/settlements/" + previous, cookie).get("propertyCountTotal").longValue())
        .isZero();

    String url = "/api/entity-lifecycle-events/" + created + "/corrections";
    Instant corrected = at(previous, 5);
    JsonNode response =
        AuthFlowSupport.json(
            mockMvc
                .perform(jsonPostAs(url, correctionBody(corrected), cookie))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supersedesEventId").value(created))
                .andExpect(jsonPath("$.effectiveAt").isNotEmpty())
                .andReturn());
    long correctionId = response.get("eventId").longValue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT correction_reason FROM entity_lifecycle_event WHERE id = ?",
                String.class,
                correctionId))
        .isEqualTo("등록일 오입력");
    assertThat(
            count(
                "SELECT property_count_total FROM monthly_settlement WHERE office_id = ?",
                officeId))
        .isEqualTo(1);
    mockMvc
        .perform(authedGet("/api/settlements/" + thisMonth(), cookie))
        .andExpect(jsonPath("$.propertyCountTotal").value(1))
        .andExpect(jsonPath("$.propertyCountNew").value(0));
    mockMvc
        .perform(authedGet("/api/settlements/" + previous, cookie))
        .andExpect(jsonPath("$.propertyCountNew").value(1));

    assertError(
        mockMvc.perform(jsonPostAs(url, correctionBody(corrected), cookie)),
        ErrorCode.LIFECYCLE_ALREADY_SUPERSEDED);
    String nextUrl = "/api/entity-lifecycle-events/" + correctionId + "/corrections";
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(nextUrl, correctionBody(Instant.now().plusSeconds(3600)), cookie)),
        "effectiveAt",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                nextUrl,
                "{\"effectiveAt\":\"" + corrected.atZone(KST).toOffsetDateTime() + "\"}",
                cookie)),
        "correctionReason",
        "REQUIRED");
    assertError(
        mockMvc.perform(
            jsonPostAs(
                nextUrl,
                correctionBody(corrected),
                cookieOf(newStaff(OfficeMembershipRole.OWNER)))),
        ErrorCode.RESOURCE_NOT_FOUND);

    jdbcTemplate.update("UPDATE property SET deleted_at = now() WHERE id = ?", property);
    Long archived =
        jdbcTemplate.queryForObject(
            "INSERT INTO entity_lifecycle_event (office_id, entity_type, property_id, event_type,"
                + " effective_at, recorded_by_membership_id) VALUES (?, 'PROPERTY', ?, 'ARCHIVED',"
                + " now(), ?) RETURNING id",
            Long.class,
            officeId,
            property,
            owner.membership().getId());
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/entity-lifecycle-events/" + archived + "/corrections",
                correctionBody(at(previous, 1)),
                cookie)),
        ErrorCode.LIFECYCLE_ORDER_CONFLICT);
    assertThat(count("SELECT count(*) FROM entity_lifecycle_event WHERE property_id = ?", property))
        .isEqualTo(3);
  }
}
