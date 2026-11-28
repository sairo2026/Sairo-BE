package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.legal.service.RetentionDeletionTargets;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class CustomerVisitPortalIntegrationTest extends VisitIntegrationTestBase {

  private static final String PHONE = "010-3333-4444";
  private static final String EMAIL = "portal-qa@example.test";
  private static final Pattern CODE = Pattern.compile("([0-9]{6})");

  @Autowired private RetentionDeletionTargets retentionTargets;

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
  void verifiedCustomerCanSaveWithdrawOnlyOwnOfficeDefaultsWithoutChangingRequestTimes()
      throws Exception {
    Fixture first = fixture();
    String endpoint = "/api/public/customer-visit-links/portal/preferences";
    mockMvc.perform(authedGet(endpoint, null)).andExpect(status().isNotFound());
    mockMvc
        .perform(jsonPostAs(portal(first.token()) + "/otp", "{}", null))
        .andExpect(status().isOk());
    Cookie session =
        mockMvc
            .perform(
                jsonPostAs(
                    portal(first.token()) + "/verify", "{\"code\":\"" + lastCode() + "\"}", null))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("SAIRO_CUSTOMER_PORTAL");
    assertThat(session).isNotNull();
    String preferenceResponse =
        mockMvc
            .perform(authedGet(endpoint, null).cookie(session))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.exists").value(false))
            .andExpect(jsonPath("$.version").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var bindingMatcher =
        Pattern.compile("\\\"sessionBinding\\\":\\\"([a-f0-9]+)\\\"").matcher(preferenceResponse);
    assertThat(bindingMatcher.find()).isTrue();
    String sessionBinding = bindingMatcher.group(1);
    var retentionMatcher =
        Pattern.compile("\\\"retentionUntil\\\":\\\"(\\d{4}-\\d{2}-\\d{2})\\\"")
            .matcher(preferenceResponse);
    assertThat(retentionMatcher.find()).isTrue();
    String displayedRetention = retentionMatcher.group(1);

    String body =
        "{\"expectedVersion\":0,\"preferredWeekdays\":[\"FRIDAY\",\"MONDAY\"],"
            + "\"preferredStartTime\":\"10:00:00\",\"preferredEndTime\":\"18:00:00\","
            + "\"acknowledgedNoticeVersion\":\""
            + NOTICE_VERSION
            + "\",\"acknowledgedPreferenceDisclosureVersion\":\"1.0\",\"sessionBinding\":\""
            + sessionBinding
            + "\"}";
    mockMvc
        .perform(jsonPutAs(endpoint, body, session, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.preferredWeekdays[0]").value("MONDAY"))
        .andExpect(jsonPath("$.preferredWeekdays[1]").value("FRIDAY"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.retentionUntil").value(displayedRetention));
    Integer acknowledged =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM consent_guest_notice_ack WHERE customer_link_id IN (SELECT id FROM visit_customer_link WHERE coordination_id = ?) AND action_type = 'PREFERENCE_SAVE' AND terms_version = ? AND supplemental_notice_version = '1.0' AND supplemental_notice_hash = ?",
            Integer.class,
            first.coordinationId(),
            NOTICE_VERSION,
            com.sairo.be.domain.visit.service.CustomerPreferenceNotice.SHA256);
    assertThat(acknowledged).isEqualTo(1);
    mockMvc
        .perform(
            jsonPutAs(endpoint, body.replace(NOTICE_VERSION, "outdated-notice"), session, null))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            jsonPutAs(
                endpoint,
                body.replace(
                    "\"acknowledgedPreferenceDisclosureVersion\":\"1.0\"",
                    "\"acknowledgedPreferenceDisclosureVersion\":\"outdated\""),
                session,
                null))
        .andExpect(status().isConflict());
    mockMvc.perform(jsonPutAs(endpoint, body, session, null)).andExpect(status().isConflict());
    mockMvc
        .perform(
            jsonPostAs(
                "/api/public/customer-visit-links/" + first.token() + "/available-times",
                timeBody(first.start()),
                null))
        .andExpect(status().isOk());
    mockMvc
        .perform(authedGet(endpoint, null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.preferredStartTime").value("10:00:00"));

    var otherOffice = newStaff(OfficeMembershipRole.OWNER);
    Cookie otherStaff = cookieOf(otherOffice);
    long otherCustomer =
        createCustomer(otherStaff, "Other office", PHONE, otherOffice.membership().getId());
    jdbcTemplate.update("UPDATE customer SET email = ? WHERE id = ?", EMAIL, otherCustomer);
    long otherCoordination =
        createCoordination(
                otherStaff,
                otherCustomer,
                null,
                first.start(),
                first.start().plusDays(3),
                newProperty(otherStaff))
            .get("coordinationId")
            .longValue();
    String otherToken = issueToken(otherStaff, otherCoordination);
    mockMvc.perform(jsonPostAs(portal(otherToken) + "/otp", "{}", null)).andExpect(status().isOk());
    Cookie otherSession =
        mockMvc
            .perform(
                jsonPostAs(
                    portal(otherToken) + "/verify", "{\"code\":\"" + lastCode() + "\"}", null))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("SAIRO_CUSTOMER_PORTAL");
    mockMvc
        .perform(authedGet(endpoint, null).cookie(otherSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exists").value(false));
    mockMvc.perform(jsonPutAs(endpoint, body, otherSession, null)).andExpect(status().isNotFound());
    mockMvc
        .perform(authedGet(portal(first.token()) + "/preferences", null).cookie(otherSession))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(authedGet(portal(first.token()) + "/preferences", null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.preferredWeekdays[0]").value("MONDAY"));

    mockMvc
        .perform(
            deleteAs(endpoint + "?expectedVersion=1", session)
                .header("X-Customer-Portal-Binding", sessionBinding))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exists").value(false))
        .andExpect(jsonPath("$.version").value(2));
    mockMvc
        .perform(authedGet(endpoint, null).cookie(session))
        .andExpect(jsonPath("$.exists").value(false));
    Integer requestSlots =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_customer_time_slot WHERE customer_link_id IN "
                + "(SELECT id FROM visit_customer_link WHERE coordination_id = ?)",
            Integer.class,
            first.coordinationId());
    assertThat(requestSlots).isEqualTo(1);

    mockMvc.perform(jsonPutAs(endpoint, body, session, null)).andExpect(status().isConflict());
    String recreate = body.replace("\"expectedVersion\":0", "\"expectedVersion\":2");
    mockMvc
        .perform(jsonPutAs(endpoint, recreate, session, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3));
    jdbcTemplate.update(
        "UPDATE customer SET retention_until = current_date - 1 WHERE id = ?", first.customerId());
    mockMvc.perform(authedGet(endpoint, null).cookie(session)).andExpect(status().isNotFound());
    Long officeId =
        jdbcTemplate.queryForObject(
            "SELECT office_id FROM customer WHERE id = ?", Long.class, first.customerId());
    assertThat(
            retentionTargets.dueCount(
                RetentionDeletionTargets.CUSTOMER_VISIT_PREFERENCE, officeId, Instant.now()))
        .isEqualTo(1);
    assertThat(
            retentionTargets.execute(
                RetentionDeletionTargets.CUSTOMER_VISIT_PREFERENCE, officeId, Instant.now()))
        .isEqualTo(1);
    Integer remaining =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM customer_visit_preference WHERE customer_id = ?",
            Integer.class,
            first.customerId());
    assertThat(remaining).isZero();
  }

  @Test
  void firstSaveThroughAnotherLinkUsesTheDeadlineShownToTheVerifiedSession() throws Exception {
    Fixture first = fixture();
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
    jdbcTemplate.update(
        "UPDATE visit_customer_link SET retention_until = ? WHERE coordination_id = ?",
        today().plusDays(1),
        secondId);
    mockMvc
        .perform(jsonPostAs(portal(first.token()) + "/otp", "{}", null))
        .andExpect(status().isOk());
    Cookie session =
        mockMvc
            .perform(
                jsonPostAs(
                    portal(first.token()) + "/verify", "{\"code\":\"" + lastCode() + "\"}", null))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("SAIRO_CUSTOMER_PORTAL");
    String endpoint = "/api/public/customer-visit-links/portal/preferences";
    String onIndex =
        mockMvc
            .perform(authedGet(endpoint, null).cookie(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String onSecondLink =
        mockMvc
            .perform(authedGet(portal(secondToken) + "/preferences", null).cookie(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var deadline = Pattern.compile("\\\"retentionUntil\\\":\\\"([^\"]+)\\\"").matcher(onIndex);
    var binding =
        Pattern.compile("\\\"sessionBinding\\\":\\\"([a-f0-9]+)\\\"").matcher(onSecondLink);
    assertThat(deadline.find()).isTrue();
    assertThat(binding.find()).isTrue();
    assertThat(onSecondLink).contains("\"retentionUntil\":\"" + deadline.group(1) + "\"");
    String body =
        "{\"expectedVersion\":0,\"preferredWeekdays\":[\"MONDAY\"],"
            + "\"acknowledgedNoticeVersion\":\""
            + NOTICE_VERSION
            + "\",\"acknowledgedPreferenceDisclosureVersion\":\"1.0\","
            + "\"sessionBinding\":\""
            + binding.group(1)
            + "\"}";
    mockMvc
        .perform(jsonPutAs(endpoint, body, session, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.retentionUntil").value(deadline.group(1)));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT retention_until FROM customer_visit_preference WHERE customer_id = ?",
                LocalDate.class,
                first.customerId()))
        .isEqualTo(LocalDate.parse(deadline.group(1)));
  }

  @Test
  void editingThroughOlderLinkDoesNotMisstateOrShortenSavedPreferenceRetention() throws Exception {
    Fixture first = fixture();
    String endpoint = "/api/public/customer-visit-links/portal/preferences";
    mockMvc
        .perform(jsonPostAs(portal(first.token()) + "/otp", "{}", null))
        .andExpect(status().isOk());
    Cookie session =
        mockMvc
            .perform(
                jsonPostAs(
                    portal(first.token()) + "/verify", "{\"code\":\"" + lastCode() + "\"}", null))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("SAIRO_CUSTOMER_PORTAL");
    String response =
        mockMvc
            .perform(authedGet(endpoint, null).cookie(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var binding = Pattern.compile("\\\"sessionBinding\\\":\\\"([a-f0-9]+)\\\"").matcher(response);
    assertThat(binding.find()).isTrue();
    String body =
        "{\"expectedVersion\":0,\"preferredWeekdays\":[\"MONDAY\"],"
            + "\"preferredStartTime\":null,\"preferredEndTime\":null,"
            + "\"acknowledgedNoticeVersion\":\""
            + NOTICE_VERSION
            + "\","
            + "\"acknowledgedPreferenceDisclosureVersion\":\"1.0\","
            + "\"sessionBinding\":\""
            + binding.group(1)
            + "\"}";
    mockMvc.perform(jsonPutAs(endpoint, body, session, null)).andExpect(status().isOk());
    LocalDate stored =
        jdbcTemplate.queryForObject(
            "SELECT retention_until FROM customer_visit_preference WHERE customer_id = ?",
            LocalDate.class,
            first.customerId());
    assertThat(stored).isAfter(today().plusDays(1));
    jdbcTemplate.update(
        "UPDATE visit_customer_link SET retention_until = ? WHERE coordination_id = ?",
        today().plusDays(1),
        first.coordinationId());
    mockMvc
        .perform(authedGet(endpoint, null).cookie(session))
        .andExpect(jsonPath("$.retentionUntil").value(stored.toString()));
    mockMvc
        .perform(
            jsonPutAs(
                endpoint,
                body.replace("\"expectedVersion\":0", "\"expectedVersion\":1"),
                session,
                null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.retentionUntil").value(stored.toString()));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT retention_until FROM customer_visit_preference WHERE customer_id = ?",
                LocalDate.class,
                first.customerId()))
        .isEqualTo(stored);
    mockMvc
        .perform(
            deleteAs(endpoint + "?expectedVersion=2", session)
                .header("X-Customer-Portal-Binding", binding.group(1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.retentionUntil").value(stored.toString()));
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
        .perform(authedGet("/api/public/customer-visit-links/portal", null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(false))
        .andExpect(jsonPath("$.items.length()").value(0));

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
        .perform(authedGet("/api/public/customer-visit-links/portal", null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.verified").value(true))
        .andExpect(jsonPath("$.items.length()").value(2));
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
    mockMvc
        .perform(authedGet("/api/public/customer-visit-links/portal", null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(true))
        .andExpect(jsonPath("$.items.length()").value(2));

    jdbcTemplate.update(
        "UPDATE customer SET email = ? WHERE id = ?", "changed@example.test", first.customerId());
    mockMvc
        .perform(authedGet(portal(secondToken), null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(true))
        .andExpect(jsonPath("$.verified").value(false))
        .andExpect(jsonPath("$.items.length()").value(0));
    mockMvc
        .perform(authedGet("/api/public/customer-visit-links/portal", null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(false));
    jdbcTemplate.update("UPDATE customer SET email = ? WHERE id = ?", EMAIL, first.customerId());

    mockMvc
        .perform(jsonPostAs("/api/public/customer-visit-links/portal/logout", "{}", session))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(authedGet(portal(secondToken), null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(false));
    mockMvc
        .perform(authedGet("/api/public/customer-visit-links/portal", null).cookie(session))
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
    mockMvc
        .perform(authedGet("/api/public/customer-visit-links/portal", null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(true));
    assertError(
        mockMvc.perform(jsonPostAs(portal(fixture.token()) + "/otp", "{}", null)),
        ErrorCode.PUBLIC_LINK_EXPIRED);
    jdbcTemplate.update(
        "UPDATE visit_customer_link SET revoked_at = now() WHERE coordination_id = ?",
        fixture.coordinationId());
    assertError(
        mockMvc.perform(authedGet(portal(fixture.token()), null).cookie(session)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
    mockMvc
        .perform(authedGet("/api/public/customer-visit-links/portal", null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(false));
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

  @Test
  void verifiedCustomerCanRespondToAnotherRequestWithoutFindingItsLink() throws Exception {
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
    issueToken(first.staff(), secondId);
    mockMvc
        .perform(jsonPostAs(portal(first.token()) + "/otp", "{}", null))
        .andExpect(status().isOk());
    Cookie session =
        mockMvc
            .perform(
                jsonPostAs(
                    portal(first.token()) + "/verify", "{\"code\":\"" + lastCode() + "\"}", null))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("SAIRO_CUSTOMER_PORTAL");
    assertThat(session).isNotNull();

    String target = "/api/public/customer-visit-links/portal/requests/" + secondId;
    assertError(mockMvc.perform(authedGet(target, null)), ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPostAs(target + "/available-times", timeBody(first.start()), null)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
    mockMvc
        .perform(authedGet(target, null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.submittedTimeSlots.length()").value(0));
    mockMvc
        .perform(authedGet(target + "/reusable-times", null).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timeSlots.length()").value(1));
    mockMvc
        .perform(jsonPostAs(target + "/available-times", timeBody(first.start()), session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.responseVersion").value(1));
    mockMvc
        .perform(authedGet(target, null).cookie(session))
        .andExpect(jsonPath("$.submittedTimeSlots.length()").value(1));
    mockMvc
        .perform(authedGet("/api/public/customer-visit-links/portal", null).cookie(session))
        .andExpect(jsonPath("$.items[0].coordinationId").value(secondId))
        .andExpect(jsonPath("$.items[0].canRespond").value(true));

    long otherCustomer =
        createCustomer(first.staff(), "Other QA", "010-4444-5555", first.managerId());
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
    issueToken(first.staff(), otherId);
    String otherTarget = "/api/public/customer-visit-links/portal/requests/" + otherId;
    assertError(
        mockMvc.perform(authedGet(otherTarget, null).cookie(session)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertError(
        mockMvc.perform(
            jsonPostAs(otherTarget + "/available-times", timeBody(first.start()), session)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);

    var otherOffice = newStaff(OfficeMembershipRole.OWNER);
    Cookie otherStaff = cookieOf(otherOffice);
    long otherOfficeCustomer =
        createCustomer(otherStaff, "Same email elsewhere", PHONE, otherOffice.membership().getId());
    jdbcTemplate.update("UPDATE customer SET email = ? WHERE id = ?", EMAIL, otherOfficeCustomer);
    long otherOfficeId =
        createCoordination(
                otherStaff,
                otherOfficeCustomer,
                null,
                first.start(),
                first.start().plusDays(3),
                newProperty(otherStaff))
            .get("coordinationId")
            .longValue();
    issueToken(otherStaff, otherOfficeId);
    String otherOfficeTarget = "/api/public/customer-visit-links/portal/requests/" + otherOfficeId;
    assertError(
        mockMvc.perform(authedGet(otherOfficeTarget, null).cookie(session)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertError(
        mockMvc.perform(
            jsonPostAs(otherOfficeTarget + "/available-times", timeBody(first.start()), session)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);

    jdbcTemplate.update(
        "UPDATE visit_customer_link SET expires_at = now() - interval '1 minute' WHERE coordination_id = ?",
        secondId);
    mockMvc
        .perform(authedGet("/api/public/customer-visit-links/portal", null).cookie(session))
        .andExpect(jsonPath("$.items[0].canRespond").value(false));
    assertError(
        mockMvc.perform(jsonPostAs(target + "/available-times", timeBody(first.start()), session)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
  }

  @Test
  void concurrentPortalSubmissionsReceiveDistinctResponseVersions() throws Exception {
    Fixture fixture = fixture();
    mockMvc
        .perform(jsonPostAs(portal(fixture.token()) + "/otp", "{}", null))
        .andExpect(status().isOk());
    Cookie session =
        mockMvc
            .perform(
                jsonPostAs(
                    portal(fixture.token()) + "/verify", "{\"code\":\"" + lastCode() + "\"}", null))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("SAIRO_CUSTOMER_PORTAL");
    String target =
        "/api/public/customer-visit-links/portal/requests/"
            + fixture.coordinationId()
            + "/available-times";
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () -> {
                start.await();
                return AuthFlowSupport.json(
                        mockMvc
                            .perform(jsonPostAs(target, timeBody(fixture.start()), session))
                            .andExpect(status().isOk())
                            .andReturn())
                    .get("responseVersion")
                    .asInt();
              });
      var second =
          pool.submit(
              () -> {
                start.await();
                return AuthFlowSupport.json(
                        mockMvc
                            .perform(jsonPostAs(target, timeBody(fixture.start()), session))
                            .andExpect(status().isOk())
                            .andReturn())
                    .get("responseVersion")
                    .asInt();
              });
      start.countDown();
      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(1, 2);
    }
    mockMvc
        .perform(authedGet("/api/public/customer-visit-links/" + fixture.token(), null))
        .andExpect(jsonPath("$.responseVersion").value(2))
        .andExpect(jsonPath("$.submittedTimeSlots.length()").value(1));
  }

  @Test
  void reissueAndPortalSubmissionCannotWriteToAnObsoleteLink() throws Exception {
    Fixture fixture = fixture();
    mockMvc
        .perform(jsonPostAs(portal(fixture.token()) + "/otp", "{}", null))
        .andExpect(status().isOk());
    Cookie session =
        mockMvc
            .perform(
                jsonPostAs(
                    portal(fixture.token()) + "/verify", "{\"code\":\"" + lastCode() + "\"}", null))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("SAIRO_CUSTOMER_PORTAL");
    String target =
        "/api/public/customer-visit-links/portal/requests/"
            + fixture.coordinationId()
            + "/available-times";
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var submission =
          pool.submit(
              () -> {
                start.await();
                return mockMvc
                    .perform(jsonPostAs(target, timeBody(fixture.start()), session))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      var reissue =
          pool.submit(
              () -> {
                start.await();
                return AuthFlowSupport.json(
                        mockMvc
                            .perform(
                                jsonPostAs(
                                    "/api/visit-coordinations/"
                                        + fixture.coordinationId()
                                        + "/customer-link/reissue",
                                    "",
                                    fixture.staff()))
                            .andExpect(status().isOk())
                            .andReturn())
                    .get("token")
                    .asString();
              });
      start.countDown();
      int submitStatus = submission.get(20, TimeUnit.SECONDS);
      String newToken = reissue.get(20, TimeUnit.SECONDS);
      assertThat(submitStatus).isIn(200, 404);
      mockMvc
          .perform(authedGet("/api/public/customer-visit-links/" + newToken, null))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.responseVersion").value(submitStatus == 200 ? 1 : 0))
          .andExpect(jsonPath("$.submittedTimeSlots.length()").value(submitStatus == 200 ? 1 : 0));
      assertError(
          mockMvc.perform(authedGet("/api/public/customer-visit-links/" + fixture.token(), null)),
          ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
  }
}
