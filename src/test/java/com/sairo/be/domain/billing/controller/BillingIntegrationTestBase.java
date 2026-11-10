package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.AuthFlowSupport.withCsrf;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.stub.StubPaymentGateway;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.fixture.AppUserBuilder;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

abstract class BillingIntegrationTestBase extends AuthIntegrationTestBase {

  @Autowired protected OfficeRepository officeRepository;
  @Autowired protected OfficeMembershipRepository officeMembershipRepository;
  @Autowired protected StubPaymentGateway paymentGateway;

  private static final AtomicInteger PLAIN_USERS = new AtomicInteger(0);

  protected BillingFixtures fixtures;

  @BeforeEach
  void resetBillingState() {
    fixtures = new BillingFixtures(jdbcTemplate);
    paymentGateway.reset();
  }

  protected TestData data() {
    return new TestData(appUserRepository, officeRepository, officeMembershipRepository);
  }

  protected Cookie cookieOf(TestData.Staff staff) {
    return sessions.cookie(
        sessions.createMemberSession(staff.principal(), Instant.now(), Instant.now()));
  }

  protected Cookie cookieWithStepUp(TestData.Staff staff) {
    Cookie cookie = cookieOf(staff);
    grantStepUp(cookie);
    return cookie;
  }

  protected TestData.Staff owner() {
    return data().approvedStaff(OfficeMembershipRole.OWNER);
  }

  protected TestData.Staff memberIn(TestData.Staff owner, OfficeMembershipRole role) {
    AppUser user = appUserRepository.saveAndFlush(AppUserBuilder.anAppUser().build());
    return data().approvedStaffIn(owner.office(), user, role);
  }

  protected static Function<Cookie, MockHttpServletRequestBuilder> getRequest(String url) {
    return cookie -> cookie == null ? get(url) : get(url).cookie(cookie);
  }

  protected static Function<Cookie, MockHttpServletRequestBuilder> deleteRequest(String url) {
    return cookie -> withCsrf(cookie == null ? delete(url) : delete(url).cookie(cookie));
  }

  protected static Function<Cookie, MockHttpServletRequestBuilder> postRequest(
      String url, String body) {
    return cookie -> cookie == null ? jsonPost(url, body) : jsonPost(url, body, cookie);
  }

  protected static Function<Cookie, MockHttpServletRequestBuilder> putRequest(
      String url, String body) {
    return cookie -> {
      MockHttpServletRequestBuilder builder =
          put(url).contentType(MediaType.APPLICATION_JSON).content(body);
      return withCsrf(cookie == null ? builder : builder.cookie(cookie));
    };
  }

  protected void assertOwnerOnly(Function<Cookie, MockHttpServletRequestBuilder> request)
      throws Exception {
    TestData.Staff owner = owner();
    TestData.Staff agent = memberIn(owner, OfficeMembershipRole.AGENT);
    TestData.Staff deputy = memberIn(owner, OfficeMembershipRole.DEPUTY);
    assertError(mockMvc.perform(request.apply(null)), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(request.apply(cookieOf(agent))), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(request.apply(cookieOf(deputy))), ErrorCode.FORBIDDEN);
    String email = "plain" + PLAIN_USERS.incrementAndGet() + "@example.com";
    saveEmailUser(email, "010-7000-" + String.format("%04d", PLAIN_USERS.get()));
    assertError(mockMvc.perform(request.apply(login(email, PASSWORD))), ErrorCode.FORBIDDEN);
  }
}
