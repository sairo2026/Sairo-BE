package com.sairo.be.support;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.global.port.stub.StubKakaoUnlink;
import com.sairo.be.global.port.stub.StubMailSender;
import com.sairo.be.global.port.stub.StubSmsSender;
import com.sairo.be.support.fixture.AppUserBuilder;
import com.sairo.be.support.fixture.TermsFixtures;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class AuthIntegrationTestBase {

  protected static final String PASSWORD = "sairo-pw-26";

  @Autowired protected MockMvc mockMvc;
  @Autowired protected JdbcTemplate jdbcTemplate;
  @Autowired protected StringRedisTemplate redisTemplate;
  @Autowired protected AppUserRepository appUserRepository;
  @Autowired protected StubMailSender mailSender;
  @Autowired protected StubSmsSender smsSender;
  @Autowired protected StubKakaoUnlink kakaoUnlink;
  @Autowired protected SessionRepository sessionRepository;

  protected AuthFlowSupport flow;
  protected SessionTestSupport sessions;

  @BeforeEach
  void resetAuthState() {
    new DatabaseCleaner(jdbcTemplate, redisTemplate).clean();
    mailSender.clear();
    smsSender.clear();
    kakaoUnlink.clear();
    TermsFixtures.seedSignupTerms(jdbcTemplate);
    flow = new AuthFlowSupport(mockMvc, mailSender, smsSender);
    sessions = new SessionTestSupport(sessionRepository);
  }

  protected AppUser saveEmailUser(String email, String phone) {
    return appUserRepository.saveAndFlush(
        AppUserBuilder.anAppUser().emailLogin(email, PASSWORD).phone(phone).name("김회원").build());
  }

  protected Cookie login(String email, String password) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                AuthFlowSupport.jsonPost(
                    "/api/auth/login",
                    "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
            .andReturn();
    return AuthFlowSupport.sessionCookie(result);
  }

  protected String signupBody(
      String emailTicket, String phoneTicket, String password, String name, String terms) {
    return "{\"verifiedEmailTicket\":\""
        + emailTicket
        + "\",\"verifiedPhoneTicket\":\""
        + phoneTicket
        + "\",\"password\":"
        + quote(password)
        + ",\"displayName\":"
        + quote(name)
        + ",\"agreedTerms\":"
        + terms
        + "}";
  }

  protected static String requiredTerms() {
    return "[{\"termsCode\":\"TOS\",\"version\":\"2026-09-01\"}]";
  }

  protected static String allTerms() {
    return "[{\"termsCode\":\"TOS\",\"version\":\"2026-09-01\"},"
        + "{\"termsCode\":\"MARKETING\",\"version\":\"2026-09-01\"}]";
  }

  protected static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
