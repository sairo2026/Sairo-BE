package com.sairo.be.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.sairo.be.global.port.MailSender.MailMessage;
import com.sairo.be.global.port.SmsSender.SmsMessage;
import com.sairo.be.global.port.stub.StubMailSender;
import com.sairo.be.global.port.stub.StubSmsSender;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class AuthFlowSupport {

  public static final String CSRF_VALUE = "test-csrf-token-value";
  public static final String SESSION_COOKIE_NAME = "__Host-sairo_session";
  private static final Pattern TOKEN_IN_MAIL = Pattern.compile("token=([A-Za-z0-9_-]+)");
  private static final Pattern CODE_IN_SMS = Pattern.compile("(\\d{6})");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final MockMvc mockMvc;
  private final StubMailSender mailSender;
  private final StubSmsSender smsSender;

  public AuthFlowSupport(MockMvc mockMvc, StubMailSender mailSender, StubSmsSender smsSender) {
    this.mockMvc = mockMvc;
    this.mailSender = mailSender;
    this.smsSender = smsSender;
  }

  public static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder builder) {
    return builder.cookie(new Cookie("XSRF-TOKEN", CSRF_VALUE)).header("X-XSRF-TOKEN", CSRF_VALUE);
  }

  public static MockHttpServletRequestBuilder jsonPost(String url, String body, Cookie... cookies) {
    MockHttpServletRequestBuilder builder =
        post(url).contentType(MediaType.APPLICATION_JSON).content(body);
    if (cookies.length > 0) {
      builder.cookie(cookies);
    }
    return withCsrf(builder);
  }

  public static MockHttpServletRequestBuilder jsonPatch(
      String url, String body, Cookie... cookies) {
    MockHttpServletRequestBuilder builder =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    if (cookies.length > 0) {
      builder.cookie(cookies);
    }
    return withCsrf(builder);
  }

  public static Cookie sessionCookie(MvcResult result) {
    return sessionCookie(result.getResponse());
  }

  public static Cookie sessionCookie(MockHttpServletResponse response) {
    return response.getCookie(SESSION_COOKIE_NAME);
  }

  public static JsonNode json(MvcResult result) throws Exception {
    return MAPPER.readTree(result.getResponse().getContentAsString());
  }

  public String lastMailToken(String email) {
    List<MailMessage> messages = mailSender.sent();
    for (int i = messages.size() - 1; i >= 0; i--) {
      MailMessage message = messages.get(i);
      if (message.to().equals(email)) {
        Matcher matcher = TOKEN_IN_MAIL.matcher(message.body());
        if (matcher.find()) {
          return matcher.group(1);
        }
      }
    }
    throw new IllegalStateException("발송된 인증 메일이 없습니다.");
  }

  public String lastSmsCode(String phone) {
    List<SmsMessage> messages = smsSender.sent();
    for (int i = messages.size() - 1; i >= 0; i--) {
      SmsMessage message = messages.get(i);
      if (message.toPhone().equals(phone)) {
        Matcher matcher = CODE_IN_SMS.matcher(message.body());
        if (matcher.find()) {
          return matcher.group(1);
        }
      }
    }
    throw new IllegalStateException("발송된 인증 문자가 없습니다.");
  }

  public String sendAndVerifyEmail(String email) throws Exception {
    mockMvc.perform(
        jsonPost("/api/auth/signup/email/verification", "{\"email\":\"" + email + "\"}"));
    String token = lastMailToken(email);
    MvcResult result =
        mockMvc
            .perform(jsonPost("/api/auth/signup/email/verify", "{\"token\":\"" + token + "\"}"))
            .andReturn();
    return json(result).get("verifiedEmailTicket").asString();
  }

  public String sendAndVerifyPhone(String phone) throws Exception {
    mockMvc.perform(
        jsonPost(
            "/api/auth/phone-verification",
            "{\"phone\":\"" + phone + "\",\"purpose\":\"SIGNUP\"}"));
    String code = lastSmsCode(phone);
    MvcResult result =
        mockMvc
            .perform(
                jsonPost(
                    "/api/auth/phone-verification/verify",
                    "{\"phone\":\""
                        + phone
                        + "\",\"purpose\":\"SIGNUP\",\"code\":\""
                        + code
                        + "\"}"))
            .andReturn();
    return json(result).get("verifiedPhoneTicket").asString();
  }

  public MvcResult getTerms() throws Exception {
    return mockMvc.perform(get("/api/terms")).andReturn();
  }

  public record KakaoStart(Cookie cookie, String state, String location) {}

  public KakaoStart startKakao(String url, Cookie existing) throws Exception {
    MockHttpServletRequestBuilder builder = get(url);
    if (existing != null) {
      builder.cookie(existing);
    }
    MvcResult result = mockMvc.perform(builder).andReturn();
    String location = result.getResponse().getHeader("Location");
    Cookie issued = sessionCookie(result);
    String state = null;
    if (location != null) {
      Matcher matcher = Pattern.compile("state=([A-Za-z0-9_-]+)").matcher(location);
      if (matcher.find()) {
        state = matcher.group(1);
      }
    }
    return new KakaoStart(issued != null ? issued : existing, state, location);
  }
}
