package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.DeskIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class PublicPrivacyIntakeIntegrationTest extends DeskIntegrationTestBase {

  private static final String URL = "/api/public/privacy-intakes";

  private record Links(TestData.Staff owner, String customerToken, String approverToken) {}

  private Links links() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    LocalDate start = today().plusDays(10);
    long coordinationId =
        createCoordination(
                cookie,
                customer,
                null,
                start,
                start.plusDays(10),
                newProperty(cookie),
                newProperty(cookie))
            .get("coordinationId")
            .longValue();
    String customerToken = issueToken(cookie, coordinationId);
    long propertyId = newProperty(cookie);
    long approvalId = registerApproval(cookie, propertyId, null, 1, 30);
    return new Links(owner, customerToken, issueApproverToken(cookie, propertyId, approvalId));
  }

  private static String body(String kind, String token, Map<String, String> overrides) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("linkKind", "\"" + kind + "\"");
    fields.put("token", "\"" + token + "\"");
    fields.put("requestType", "\"ACCESS\"");
    fields.put("requesterRole", "\"SELF\"");
    fields.put("contactChannel", "\"EMAIL\"");
    fields.put("contact", "\"hong@example.com\"");
    fields.put("requestNote", "\"제가 입력한 방문 조건을 확인하고 싶습니다\"");
    fields.put("acknowledgedNoticeVersion", "\"" + NOTICE_VERSION + "\"");
    overrides.forEach(
        (key, value) -> {
          if (value == null) {
            fields.remove(key);
          } else {
            fields.put(key, value);
          }
        });
    return fields.entrySet().stream()
        .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
        .collect(Collectors.joining(",", "{", "}"));
  }

  private ResultActions send(String body) throws Exception {
    return mockMvc.perform(AuthFlowSupport.jsonPost(URL, body));
  }

  private ResultActions sendCustomer(String token, Map<String, String> overrides) throws Exception {
    return send(body("CUSTOMER", token, overrides));
  }

  @Test
  void 고객_링크로_접수하면_접수_시각과_결과_목표_일자와_마스킹_연락처만_돌려준다() throws Exception {
    Links links = links();

    var result =
        sendCustomer(links.customerToken(), Map.of())
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.contactChannel").value("EMAIL"))
            .andExpect(jsonPath("$.maskedContact").value("ho***@example.com"))
            .andReturn();

    JsonNode json = AuthFlowSupport.json(result);
    Set<String> keys = new HashSet<>();
    json.propertyNames().forEach(keys::add);
    assertThat(keys)
        .containsExactlyInAnyOrder("receivedAt", "resultDueAt", "contactChannel", "maskedContact");
    OffsetDateTime received = OffsetDateTime.parse(json.get("receivedAt").asString());
    OffsetDateTime due = OffsetDateTime.parse(json.get("resultDueAt").asString());
    assertThat(Duration.between(received, due)).isEqualTo(Duration.ofDays(10));
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("hong@example.com", links.customerToken());
  }

  @Test
  void 접수는_연락처를_암호화해_저장하고_안내_확인과_접속_로그를_함께_남긴다() throws Exception {
    Links links = links();

    sendCustomer(
            links.customerToken(),
            Map.of("requestType", "\"DELETION\"", "requesterRole", "\"AGENT\""))
        .andExpect(status().isCreated());

    Map<String, Object> intake =
        jdbcTemplate.queryForMap("SELECT * FROM legal_guest_privacy_intake");
    assertThat(intake.get("office_id")).isEqualTo(links.owner().office().getId());
    assertThat(intake.get("link_kind")).isEqualTo("CUSTOMER");
    assertThat(intake.get("request_type")).isEqualTo("DELETION");
    assertThat(intake.get("requester_role")).isEqualTo("AGENT");
    assertThat(intake.get("contact_channel")).isEqualTo("EMAIL");
    assertThat((String) intake.get("contact_encrypted"))
        .isNotBlank()
        .doesNotContain("hong@example.com");
    assertThat(intake.get("contact_wiped_at")).isNull();
    assertThat(intake.get("verification_status")).isEqualTo("PENDING");
    assertThat(intake.get("status")).isEqualTo("RECEIVED");
    assertThat(intake.get("privacy_request_id")).isNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT internal_due_at - received_at = interval '7 days'"
                    + " FROM legal_guest_privacy_intake",
                Boolean.class))
        .isTrue();

    Map<String, Object> ack = jdbcTemplate.queryForMap("SELECT * FROM consent_guest_notice_ack");
    assertThat(ack.get("terms_code")).isEqualTo("GUEST_LINK_NOTICE_CUSTOMER");
    assertThat(ack.get("terms_version")).isEqualTo(NOTICE_VERSION);
    assertThat(ack.get("action_type")).isEqualTo("PRIVACY_INTAKE");
    assertThat(ack.get("link_kind")).isEqualTo("CUSTOMER");
    assertThat(ack.get("customer_link_id")).isEqualTo(intake.get("link_id"));
    assertThat(ack.get("office_id")).isEqualTo(links.owner().office().getId());

    Map<String, Object> log =
        jdbcTemplate.queryForMap("SELECT * FROM legal_public_link_access_log");
    assertThat(log.get("action_type")).isEqualTo("PRIVACY_INTAKE");
    assertThat(((Number) log.get("http_status")).intValue()).isEqualTo(201);
    assertThat(log.get("link_kind")).isEqualTo("CUSTOMER");
    assertThat(log.get("link_id")).isEqualTo(intake.get("link_id"));
    assertThat((String) log.get("ip_hmac")).matches("[0-9a-f]{64}").doesNotContain("127.0.0.1");
    assertThat(((Number) log.get("hmac_key_version")).intValue()).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT purge_after - (accessed_at AT TIME ZONE 'Asia/Seoul')::date"
                    + " FROM legal_public_link_access_log",
                Integer.class))
        .isEqualTo(365);
  }

  @Test
  void 접수_행에는_링크_토큰_원문이_남지_않는다() throws Exception {
    Links links = links();
    sendCustomer(links.customerToken(), Map.of()).andExpect(status().isCreated());

    String dump =
        jdbcTemplate.queryForObject(
            "SELECT row_to_json(i)::text || row_to_json(a)::text || row_to_json(l)::text"
                + " FROM legal_guest_privacy_intake i, consent_guest_notice_ack a,"
                + " legal_public_link_access_log l",
            String.class);
    assertThat(dump).doesNotContain(links.customerToken());
  }

  @Test
  void 승인자_매물_링크로도_접수하고_승인자_안내_버전으로_대조한다() throws Exception {
    Links links = links();

    send(body("APPROVER_PROPERTY", links.approverToken(), Map.of()))
        .andExpect(status().isCreated());

    Map<String, Object> intake =
        jdbcTemplate.queryForMap("SELECT * FROM legal_guest_privacy_intake");
    assertThat(intake.get("link_kind")).isEqualTo("APPROVER_PROPERTY");
    assertThat(intake.get("office_id")).isEqualTo(links.owner().office().getId());
    Map<String, Object> ack = jdbcTemplate.queryForMap("SELECT * FROM consent_guest_notice_ack");
    assertThat(ack.get("terms_code")).isEqualTo("GUEST_LINK_NOTICE_APPROVER");
    assertThat(ack.get("approver_property_link_id")).isEqualTo(intake.get("link_id"));
    assertThat(ack.get("customer_link_id")).isNull();
  }

  @Test
  void 전화번호_접수는_정규화해_저장하고_뒤_네_자리만_보여_준다() throws Exception {
    Links links = links();

    sendCustomer(
            links.customerToken(),
            Map.of("contactChannel", "\"PHONE\"", "contact", "\"01012345678\""))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.contactChannel").value("PHONE"))
        .andExpect(jsonPath("$.maskedContact").value("010-****-5678"));
  }

  @Test
  void 요청_내용은_선택이다() throws Exception {
    Links links = links();

    sendCustomer(links.customerToken(), Map.of("requestNote", "null"))
        .andExpect(status().isCreated());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT request_note IS NULL FROM legal_guest_privacy_intake", Boolean.class))
        .isTrue();
  }

  @Test
  void 필수_필드가_없거나_허용되지_않는_값이면_400이다() throws Exception {
    Links links = links();
    String token = links.customerToken();

    assertFieldError(sendCustomer(token, mapWithNull("requestType")), "requestType", "REQUIRED");
    assertFieldError(
        sendCustomer(token, mapWithNull("requesterRole")), "requesterRole", "REQUIRED");
    assertFieldError(sendCustomer(token, mapWithNull("contact")), "contact", "REQUIRED");
    assertFieldError(
        sendCustomer(token, mapWithNull("acknowledgedNoticeVersion")),
        "acknowledgedNoticeVersion",
        "REQUIRED");
    assertFieldError(
        sendCustomer(token, Map.of("requestType", "\"WITHDRAWAL\"")), "requestType", "NOT_ALLOWED");
    assertFieldError(
        sendCustomer(token, Map.of("requestType", "\"PORTABILITY\"")),
        "requestType",
        "NOT_ALLOWED");
    assertFieldError(send(body("APPROVER_PORTAL", token, Map.of())), "linkKind", "NOT_ALLOWED");
    assertFieldError(
        sendCustomer(token, Map.of("requesterRole", "\"PROXY\"")), "requesterRole", "NOT_ALLOWED");
    assertFieldError(
        sendCustomer(token, Map.of("privacyRequestId", "5")), "privacyRequestId", "NOT_ALLOWED");
    assertFieldError(
        sendCustomer(token, Map.of("requestNote", "\"" + "가".repeat(1001) + "\"")),
        "requestNote",
        "TOO_LONG");
    assertThat(count("legal_guest_privacy_intake")).isZero();
  }

  private static Map<String, String> mapWithNull(String key) {
    Map<String, String> map = new LinkedHashMap<>();
    map.put(key, null);
    return map;
  }

  @Test
  void 요청_내용에_주민등록번호나_계좌_번호가_있으면_400_SENSITIVE_DATA_DETECTED다() throws Exception {
    Links links = links();

    assertError(
        sendCustomer(links.customerToken(), Map.of("requestNote", "\"주민번호 900101-1234567 입니다\"")),
        ErrorCode.SENSITIVE_DATA_DETECTED);
    assertError(
        sendCustomer(links.customerToken(), Map.of("requestNote", "\"계좌 110-123-456789 입니다\"")),
        ErrorCode.SENSITIVE_DATA_DETECTED);
    assertThat(count("legal_guest_privacy_intake")).isZero();
  }

  @Test
  void 연락처_형식이_틀리면_422_GUEST_CONTACT_INVALID다() throws Exception {
    Links links = links();
    String token = links.customerToken();

    assertError(
        sendCustomer(token, Map.of("contact", "\"not-an-email\"")),
        ErrorCode.GUEST_CONTACT_INVALID);
    assertError(
        sendCustomer(token, Map.of("contactChannel", "\"PHONE\"", "contact", "\"02-123-4567\"")),
        ErrorCode.GUEST_CONTACT_INVALID);
    assertError(
        sendCustomer(
            token, Map.of("contactChannel", "\"PHONE\"", "contact", "\"hong@example.com\"")),
        ErrorCode.GUEST_CONTACT_INVALID);
    assertError(
        sendCustomer(token, Map.of("contact", "\"010-1234-5678\"")),
        ErrorCode.GUEST_CONTACT_INVALID);
    assertThat(count("legal_guest_privacy_intake")).isZero();
  }

  @Test
  void 토큰이_없거나_틀리거나_폐기됐거나_종류가_다르면_404_PUBLIC_LINK_NOT_FOUND다() throws Exception {
    Links links = links();

    assertError(sendCustomer("unknown-token", Map.of()), ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertError(
        send(body("APPROVER_PROPERTY", "unknown-token", Map.of())),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertError(
        send(body("APPROVER_PROPERTY", links.customerToken(), Map.of())),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertError(
        send(body("CUSTOMER", links.approverToken(), Map.of())), ErrorCode.PUBLIC_LINK_NOT_FOUND);
    jdbcTemplate.update("UPDATE visit_customer_link SET revoked_at = now()");
    jdbcTemplate.update("UPDATE visit_approver_property_link SET revoked_at = now()");
    assertError(sendCustomer(links.customerToken(), Map.of()), ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertError(
        send(body("APPROVER_PROPERTY", links.approverToken(), Map.of())),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertThat(count("legal_guest_privacy_intake")).isZero();
  }

  @Test
  void 만료된_링크는_404_PUBLIC_LINK_EXPIRED다() throws Exception {
    Links links = links();
    jdbcTemplate.update(
        "UPDATE visit_customer_link SET expires_at = now() - interval '1 minute',"
            + " issued_at = now() - interval '2 minutes'");
    jdbcTemplate.update(
        "UPDATE visit_approver_property_link SET expires_at = now() - interval '1 minute',"
            + " issued_at = now() - interval '2 minutes'");

    assertError(sendCustomer(links.customerToken(), Map.of()), ErrorCode.PUBLIC_LINK_EXPIRED);
    assertError(
        send(body("APPROVER_PROPERTY", links.approverToken(), Map.of())),
        ErrorCode.PUBLIC_LINK_EXPIRED);
    assertThat(count("legal_guest_privacy_intake")).isZero();
  }

  @Test
  void 화면이_보여_준_안내_버전이_시행_버전과_다르면_409이고_아무것도_남기지_않는다() throws Exception {
    Links links = links();

    assertError(
        sendCustomer(links.customerToken(), Map.of("acknowledgedNoticeVersion", "\"0.9\"")),
        ErrorCode.NOTICE_VERSION_CHANGED);
    assertThat(count("legal_guest_privacy_intake")).isZero();
    assertThat(count("consent_guest_notice_ack")).isZero();
    assertThat(count("legal_public_link_access_log")).isZero();

    jdbcTemplate.update(
        "INSERT INTO terms_catalog (terms_code, terms_version, title, category, required,"
            + " document_url, content_hash, effective_at, display_context, reconsent_required)"
            + " VALUES ('GUEST_LINK_NOTICE_CUSTOMER', '1.1', '고객 링크 개인정보 안내', 'NOTICE', false,"
            + " '/legal/link-notice/customer', ?, now() - interval '1 hour', 'LINK_NOTICE', false)",
        "d".repeat(64));
    assertError(sendCustomer(links.customerToken(), Map.of()), ErrorCode.NOTICE_VERSION_CHANGED);
    sendCustomer(links.customerToken(), Map.of("acknowledgedNoticeVersion", "\"1.1\""))
        .andExpect(status().isCreated());
  }

  @Test
  void 같은_링크는_하루_3건까지만_접수하고_넘으면_429다() throws Exception {
    Links links = links();
    for (int i = 0; i < 3; i++) {
      sendCustomer(links.customerToken(), Map.of()).andExpect(status().isCreated());
    }

    assertError(sendCustomer(links.customerToken(), Map.of()), ErrorCode.RATE_LIMITED);
    assertThat(count("legal_guest_privacy_intake")).isEqualTo(3);
  }

  @Test
  void 같은_IP는_한_시간에_5건까지만_접수하고_넘으면_링크가_달라도_429다() throws Exception {
    Links first = links();
    Links second = links();
    for (int i = 0; i < 3; i++) {
      sendCustomer(first.customerToken(), Map.of()).andExpect(status().isCreated());
    }
    for (int i = 0; i < 2; i++) {
      sendCustomer(second.customerToken(), Map.of()).andExpect(status().isCreated());
    }

    assertError(sendCustomer(second.customerToken(), Map.of()), ErrorCode.RATE_LIMITED);
    assertThat(count("legal_guest_privacy_intake")).isEqualTo(5);
  }
}
