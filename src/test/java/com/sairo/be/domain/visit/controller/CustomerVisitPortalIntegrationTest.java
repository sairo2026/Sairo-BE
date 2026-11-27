package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

@IntegrationTest
class CustomerVisitPortalIntegrationTest extends VisitIntegrationTestBase {

  private static final String PHONE = "010-3333-4444";
  private static final String EMAIL = "portal-qa@example.test";
  private static final Pattern CODE = Pattern.compile("([0-9]{6})");

  private record Fixture(
      Cookie staff,
      long customerId,
      long managerId,
      long coordinationId,
      String token,
      LocalDate start) {}

  private Fixture fixture() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie staff = cookieOf(owner);
    long customerId = createCustomer(staff, "Portal QA", PHONE, owner.membership().getId());
    jdbcTemplate.update("UPDATE customer SET email = ? WHERE id = ?", EMAIL, customerId);
    LocalDate start = today().plusDays(10);
    long coordinationId =
        createCoordination(staff, customerId, null, start, start.plusDays(3), newProperty(staff))
            .get("coordinationId")
            .longValue();
    return new Fixture(
        staff,
        customerId,
        owner.membership().getId(),
        coordinationId,
        issueToken(staff, coordinationId),
        start);
  }

  private static String portal(String token) {
    return "/api/public/customer-visit-links/" + token + "/portal";
  }

  private static String timeBody(LocalDate date) {
    return "{\"timeSlots\":[{\"startsAt\":\""
        + date
        + "T10:00:00+09:00\","
        + "\"endsAt\":\""
        + date
        + "T12:00:00+09:00\"}],"
        + "\"acknowledgedNoticeVersion\":\""
        + NOTICE_VERSION
        + "\"}";
  }

  private String lastCode() {
    return mailSender.sent().stream()
        .filter(message -> EMAIL.equals(message.to()))
        .map(message -> CODE.matcher(message.body()))
        .filter(java.util.regex.Matcher::find)
        .reduce((a, b) -> b)
        .orElseThrow()
        .group(1);
  }

  @Test
  void otpOpensOnlyTheSameCustomerAndOfficeRequestsWithoutAnyPreviousLink() throws Exception {
    Fixture first = fixture();
    mockMvc
        .perform(
            jsonPostAs(
                "/api/public/customer-visit-links/" + first.token() + "/available-times",
                timeBody(first.start()),
                null))
        .andExpect(status().isOk());
    long secondId =
        createCoordination(
                first.staff(),
                first.customerId(),
                null,
                first.start(),
                first.start().plusDays(3),
                newProperty(first.staff()))
            .get("coordinationId")
            .longValue();
    String secondToken = issueToken(first.staff(), secondId);

    mockMvc
        .perform(authedGet(portal(secondToken), null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(true))
        .andExpect(jsonPath("$.verified").value(false))
        .andExpect(jsonPath("$.items.length()").value(0));
    mockMvc
        .perform(jsonPostAs(portal(secondToken) + "/otp", "{}", null))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.maskedEmail").value("p***@example.test"));
    String code = lastCode();
    var verified =
        mockMvc
            .perform(
                jsonPostAs(portal(secondToken) + "/verify", "{\"code\":\"" + code + "\"}", null))
            .andExpect(status().isOk())
            .andReturn();
    String setCookie = verified.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie)
        .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/public/customer-visit-links");
    Cookie session = verified.getResponse().getCookie("SAIRO_CUSTOMER_PORTAL");
    assertThat(session).isNotNull();
    mockMvc
        .perform(authedGet(portal(secondToken), null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].coordinationId").value(secondId));
    mockMvc
        .perform(authedGet(portal(secondToken) + "/reusable-times", null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timeSlots.length()").value(1));
    jdbcTemplate.update(
        "UPDATE visit_customer_link SET retention_until = current_date - interval '1 day' WHERE coordination_id = ?",
        first.coordinationId());
    mockMvc
        .perform(authedGet(portal(secondToken) + "/reusable-times", null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timeSlots.length()").value(0));
    mockMvc
        .perform(authedGet("/api/public/customer-visit-links/" + secondToken, null))
        .andExpect(jsonPath("$.submittedTimeSlots.length()").value(0));

    long otherCustomer =
        createCustomer(first.staff(), "Other QA", "010-4444-5555", first.managerId());
    jdbcTemplate.update(
        "UPDATE customer SET email = ? WHERE id = ?", "other@example.test", otherCustomer);
    long otherId =
        createCoordination(
                first.staff(),
                otherCustomer,
                null,
                first.start(),
                first.start().plusDays(3),
                newProperty(first.staff()))
            .get("coordinationId")
            .longValue();
    String otherToken = issueToken(first.staff(), otherId);
    mockMvc
        .perform(authedGet(portal(otherToken), null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(false))
        .andExpect(jsonPath("$.items.length()").value(0));

    var otherOffice = newStaff(OfficeMembershipRole.OWNER);
    Cookie otherStaff = cookieOf(otherOffice);
    long otherOfficeCustomer =
        createCustomer(otherStaff, "Same phone elsewhere", PHONE, otherOffice.membership().getId());
    jdbcTemplate.update("UPDATE customer SET email = ? WHERE id = ?", EMAIL, otherOfficeCustomer);
    long otherOfficeCoordination =
        createCoordination(
                otherStaff,
                otherOfficeCustomer,
                null,
                first.start(),
                first.start().plusDays(3),
                newProperty(otherStaff))
            .get("coordinationId")
            .longValue();
    String otherOfficeToken = issueToken(otherStaff, otherOfficeCoordination);
    mockMvc
        .perform(authedGet(portal(otherOfficeToken), null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(false))
        .andExpect(jsonPath("$.items.length()").value(0));

    jdbcTemplate.update(
        "UPDATE customer SET email = ? WHERE id = ?", "changed@example.test", first.customerId());
    mockMvc
        .perform(authedGet(portal(secondToken), null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(true))
        .andExpect(jsonPath("$.verified").value(false))
        .andExpect(jsonPath("$.items.length()").value(0));
    jdbcTemplate.update("UPDATE customer SET email = ? WHERE id = ?", EMAIL, first.customerId());

    mockMvc
        .perform(jsonPostAs(portal(secondToken) + "/logout", "{}", session))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(authedGet(portal(secondToken), null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(false));
  }

  @Test
  void wrongCodeCannotCreateASessionAndExpiredOrRevokedLinksCannotStartVerification()
      throws Exception {
    Fixture fixture = fixture();
    mockMvc
        .perform(jsonPostAs(portal(fixture.token()) + "/otp", "{}", null))
        .andExpect(status().isOk());
    String code = lastCode();
    String wrong = code.equals("000000") ? "111111" : "000000";
    assertError(
        mockMvc.perform(
            jsonPostAs(portal(fixture.token()) + "/verify", "{\"code\":\"" + wrong + "\"}", null)),
        ErrorCode.TOKEN_INVALID);
    var verified =
        mockMvc
            .perform(
                jsonPostAs(
                    portal(fixture.token()) + "/verify", "{\"code\":\"" + code + "\"}", null))
            .andExpect(status().isOk())
            .andReturn();
    Cookie session = verified.getResponse().getCookie("SAIRO_CUSTOMER_PORTAL");
    assertError(
        mockMvc.perform(
            jsonPostAs(portal(fixture.token()) + "/verify", "{\"code\":\"" + code + "\"}", null)),
        ErrorCode.TOKEN_INVALID);

    jdbcTemplate.update(
        "UPDATE visit_customer_link SET expires_at = now() - interval '1 minute' WHERE coordination_id = ?",
        fixture.coordinationId());
    // An already verified customer may still read the bookmarked portal after the response link
    // expires.
    mockMvc
        .perform(authedGet(portal(fixture.token()), null).cookie(session))
        .andExpect(status().isOk());
    assertError(
        mockMvc.perform(jsonPostAs(portal(fixture.token()) + "/otp", "{}", null)),
        ErrorCode.PUBLIC_LINK_EXPIRED);
    jdbcTemplate.update(
        "UPDATE visit_customer_link SET revoked_at = now() WHERE coordination_id = ?",
        fixture.coordinationId());
    assertError(
        mockMvc.perform(authedGet(portal(fixture.token()), null).cookie(session)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
  }

  @Test
  void customerWithoutEmailKeepsTheExistingGuestLinkAndCannotRequestAnOtp() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie staff = cookieOf(owner);
    long customerId = createCustomer(staff, "No email QA", PHONE, owner.membership().getId());
    LocalDate start = today().plusDays(10);
    long coordinationId =
        createCoordination(staff, customerId, null, start, start.plusDays(3), newProperty(staff))
            .get("coordinationId")
            .longValue();
    String token = issueToken(staff, coordinationId);
    mockMvc
        .perform(authedGet(portal(token), null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.items.length()").value(0));
    assertError(
        mockMvc.perform(jsonPostAs(portal(token) + "/otp", "{}", null)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
    mockMvc
        .perform(authedGet("/api/public/customer-visit-links/" + token, null))
        .andExpect(status().isOk());
  }

  @Test
  void fiveWrongCodesInvalidateTheIssuedCode() throws Exception {
    Fixture fixture = fixture();
    mockMvc
        .perform(jsonPostAs(portal(fixture.token()) + "/otp", "{}", null))
        .andExpect(status().isOk());
    String code = lastCode();
    String wrong = code.equals("000000") ? "111111" : "000000";
    for (int attempt = 0; attempt < 5; attempt++) {
      assertError(
          mockMvc.perform(
              jsonPostAs(
                  portal(fixture.token()) + "/verify", "{\"code\":\"" + wrong + "\"}", null)),
          ErrorCode.TOKEN_INVALID);
    }
    assertError(
        mockMvc.perform(
            jsonPostAs(portal(fixture.token()) + "/verify", "{\"code\":\"" + code + "\"}", null)),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void codeSentToAnOldEmailCannotVerifyAfterTheAddressChanges() throws Exception {
    Fixture fixture = fixture();
    mockMvc
        .perform(jsonPostAs(portal(fixture.token()) + "/otp", "{}", null))
        .andExpect(status().isOk());
    String oldCode = lastCode();
    jdbcTemplate.update(
        "UPDATE customer SET email = ? WHERE id = ?", "new@example.test", fixture.customerId());
    assertError(
        mockMvc.perform(
            jsonPostAs(
                portal(fixture.token()) + "/verify", "{\"code\":\"" + oldCode + "\"}", null)),
        ErrorCode.TOKEN_INVALID);
    mockMvc
        .perform(authedGet(portal(fixture.token()), null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(false));
  }
}
