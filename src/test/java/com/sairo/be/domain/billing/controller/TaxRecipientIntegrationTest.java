package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@IntegrationTest
class TaxRecipientIntegrationTest extends BillingIntegrationTestBase {

  private static final String URL = "/api/billing/tax-recipient";

  private static String body(String businessNumber, String email, String taxType) {
    return "{\"businessNumber\":\""
        + businessNumber
        + "\",\"companyName\":\"상호 예시\",\"representativeName\":\"대표 예시\",\"receiverEmail\":\""
        + email
        + "\",\"taxType\":\""
        + taxType
        + "\",\"issueRequested\":true}";
  }

  @Test
  void 저장된_값이_없으면_사무소_정보_기반_초기값을_제안한다() throws Exception {
    TestData.Staff owner = owner();
    mockMvc
        .perform(getRequest(URL).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registered").value(false))
        .andExpect(jsonPath("$.recipientId").value(nullValue()))
        .andExpect(
            jsonPath("$.businessNumber").value(owner.office().getBusinessRegistrationNumber()))
        .andExpect(jsonPath("$.companyName").value(owner.office().getName()))
        .andExpect(jsonPath("$.representativeName").value(owner.office().getRepresentativeName()))
        .andExpect(jsonPath("$.receiverEmail").value(nullValue()))
        .andExpect(jsonPath("$.taxType").value("UNKNOWN"))
        .andExpect(jsonPath("$.issueRequested").value(true))
        .andExpect(jsonPath("$.registeredAt").value(nullValue()));
  }

  @Test
  void 구독이_없는_사무소도_저장하고_다시_조회할_수_있다() throws Exception {
    TestData.Staff owner = owner();
    mockMvc
        .perform(
            putRequest(URL, body("1234567891", "tax@example.com", "UNKNOWN"))
                .apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registered").value(true))
        .andExpect(jsonPath("$.recipientId").isNumber())
        .andExpect(jsonPath("$.businessNumber").value("1234567891"))
        .andExpect(jsonPath("$.companyName").value("상호 예시"))
        .andExpect(jsonPath("$.representativeName").value("대표 예시"))
        .andExpect(jsonPath("$.receiverEmail").value("tax@example.com"))
        .andExpect(jsonPath("$.taxType").value("UNKNOWN"))
        .andExpect(jsonPath("$.issueRequested").value(true))
        .andExpect(jsonPath("$.registeredAt").exists());
    mockMvc
        .perform(getRequest(URL).apply(cookieOf(owner)))
        .andExpect(jsonPath("$.registered").value(true))
        .andExpect(jsonPath("$.receiverEmail").value("tax@example.com"));
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT registered_by_membership_id, status FROM tax_invoice_recipient WHERE office_id = ?",
            owner.office().getId());
    assertThat(row.get("registered_by_membership_id")).isEqualTo(owner.membership().getId());
    assertThat(row.get("status")).isEqualTo("ACTIVE");
  }

  @Test
  void 다시_저장하면_이전_활성_행을_대체됨으로_바꾸고_새_행만_활성이다() throws Exception {
    TestData.Staff owner = owner();
    mockMvc
        .perform(
            putRequest(URL, body("1234567891", "first@example.com", "GENERAL"))
                .apply(cookieOf(owner)))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            putRequest(URL, body("7041602872", "second@example.com", "SIMPLIFIED"))
                .apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.businessNumber").value("7041602872"))
        .andExpect(jsonPath("$.taxType").value("SIMPLIFIED"));
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT status, receiver_email, superseded_at FROM tax_invoice_recipient WHERE office_id = ?"
                + " ORDER BY id",
            owner.office().getId());
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("status")).isEqualTo("SUPERSEDED");
    assertThat(rows.get(0).get("superseded_at")).isNotNull();
    assertThat(rows.get(1).get("status")).isEqualTo("ACTIVE");
    assertThat(rows.get(1).get("receiver_email")).isEqualTo("second@example.com");
    mockMvc
        .perform(getRequest(URL).apply(cookieOf(owner)))
        .andExpect(jsonPath("$.receiverEmail").value("second@example.com"));
  }

  @Test
  void 사업자등록번호_형식이나_검증번호가_틀리면_422_TAX_RECIPIENT_INVALID이다() throws Exception {
    TestData.Staff owner = owner();
    for (String invalid :
        new String[] {"1234567890", "123456789", "12345678912", "123-45-67891", "abcdefghij"}) {
      assertError(
          mockMvc.perform(
              putRequest(URL, body(invalid, "tax@example.com", "UNKNOWN")).apply(cookieOf(owner))),
          ErrorCode.TAX_RECIPIENT_INVALID);
    }
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tax_invoice_recipient", Integer.class))
        .isZero();
  }

  @Test
  void 이메일_형식이_틀리면_422_TAX_RECIPIENT_INVALID이다() throws Exception {
    TestData.Staff owner = owner();
    for (String invalid : new String[] {"plain", "a@b", "a b@example.com", "@example.com"}) {
      assertError(
          mockMvc.perform(
              putRequest(URL, body("1234567891", invalid, "UNKNOWN")).apply(cookieOf(owner))),
          ErrorCode.TAX_RECIPIENT_INVALID);
    }
  }

  @Test
  void 필수_필드가_없거나_길이를_넘거나_과세유형이_허용값이_아니면_400이다() throws Exception {
    TestData.Staff owner = owner();
    assertError(
        mockMvc.perform(
            putRequest(URL, "{\"businessNumber\":\"1234567891\"}").apply(cookieOf(owner))),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            putRequest(URL, body("1234567891", "tax@example.com", "NOPE")).apply(cookieOf(owner))),
        ErrorCode.INVALID_REQUEST);
    String longName = "가".repeat(101);
    assertError(
        mockMvc.perform(
            putRequest(
                    URL,
                    "{\"businessNumber\":\"1234567891\",\"companyName\":\""
                        + longName
                        + "\",\"representativeName\":\"대표\",\"receiverEmail\":\"a@example.com\","
                        + "\"taxType\":\"UNKNOWN\",\"issueRequested\":true}")
                .apply(cookieOf(owner))),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            putRequest(
                    URL,
                    "{\"businessNumber\":\"1234567891\",\"companyName\":\"상호\","
                        + "\"representativeName\":\"대표\",\"receiverEmail\":\"a@example.com\","
                        + "\"taxType\":\"UNKNOWN\"}")
                .apply(cookieOf(owner))),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 다른_사무소의_수신_정보는_보이지_않는다() throws Exception {
    TestData.Staff first = owner();
    mockMvc
        .perform(
            putRequest(URL, body("1234567891", "tax@example.com", "UNKNOWN"))
                .apply(cookieOf(first)))
        .andExpect(status().isOk());
    TestData.Staff second = owner();
    mockMvc
        .perform(getRequest(URL).apply(cookieOf(second)))
        .andExpect(jsonPath("$.registered").value(false));
  }

  @Test
  void 수신_정보_조회와_저장은_대표만_할_수_있다() throws Exception {
    assertOwnerOnly(getRequest(URL));
    assertOwnerOnly(putRequest(URL, body("1234567891", "tax@example.com", "UNKNOWN")));
  }
}
