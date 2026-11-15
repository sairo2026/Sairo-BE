package com.sairo.be.domain.customer.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.support.CustomerIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

@IntegrationTest
class CustomerPhoneFormatIntegrationTest extends CustomerIntegrationTestBase {

  @Test
  void 등록은_숫자_7자_이상이면_하이픈_공백_괄호_국제번호_접두를_받는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    for (String phone : new String[] {"02-1234-5678", "+82 (2) 123-4567", "1234567"}) {
      mockMvc
          .perform(
              jsonPostAs(
                  "/api/customers",
                  customerBody("김번호", phone, owner.membership().getId(), ""),
                  cookie))
          .andExpect(status().isCreated());
    }
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customer WHERE phone = '+82 (2) 123-4567'", Long.class))
        .isEqualTo(1L);
  }

  @Test
  void 등록은_숫자가_7자_미만이거나_없는_연락처를_필드_오류로_거절한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    for (String phone : new String[] {"123456", "---", "연락처 없음", "abc1234567"}) {
      assertFieldError(
          mockMvc.perform(
              jsonPostAs(
                  "/api/customers",
                  customerBody("김번호", phone, owner.membership().getId(), ""),
                  cookie)),
          "phone",
          "INVALID_FORMAT");
    }
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/customers",
                customerBody(
                    "김번호",
                    "010-1111-2222",
                    owner.membership().getId(),
                    ",\"secondaryPhone\":\"12-34\""),
                cookie)),
        "secondaryPhone",
        "INVALID_FORMAT");
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM customer", Long.class)).isZero();
  }

  @Test
  void 수정은_바꾸는_연락처만_검사하고_바꾸지_않는_기존_위반_값은_막지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createCustomer(cookie, "김번호", "010-1111-2222", owner.membership().getId());
    jdbcTemplate.update("UPDATE customer SET phone = '02-12' WHERE id = ?", id);
    String url = "/api/customers/" + id;

    mockMvc
        .perform(jsonPatchAs(url, "{\"memo\":\"그대로\"}", cookie, customerVersion(id)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phone").value("02-12"));
    mockMvc
        .perform(jsonPatchAs(url, "{\"phone\":\"02-12\"}", cookie, customerVersion(id)))
        .andExpect(status().isOk());
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"phone\":\"abc\"}", cookie, customerVersion(id))),
        "phone",
        "INVALID_FORMAT");
    assertFieldError(
        mockMvc.perform(
            jsonPatchAs(url, "{\"secondaryPhone\":\"12\"}", cookie, customerVersion(id))),
        "secondaryPhone",
        "INVALID_FORMAT");
    mockMvc
        .perform(jsonPatchAs(url, "{\"phone\":\"02-1234-5678\"}", cookie, customerVersion(id)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phone").value("02-1234-5678"));
  }
}
