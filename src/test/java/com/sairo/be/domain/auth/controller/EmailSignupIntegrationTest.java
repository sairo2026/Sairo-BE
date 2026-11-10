package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.SignedTicketService;
import com.sairo.be.global.security.TicketType;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@IntegrationTest
class EmailSignupIntegrationTest extends AuthIntegrationTestBase {

  private static final String EMAIL = "agent@example.com";
  private static final String PHONE = "010-1234-5678";

  @Autowired private SignedTicketService ticketService;

  private MvcResult signup(String password, String name, String terms) throws Exception {
    String emailTicket = flow.sendAndVerifyEmail(EMAIL);
    String phoneTicket = flow.sendAndVerifyPhone(PHONE);
    return mockMvc
        .perform(
            jsonPost(
                "/api/auth/signup/email",
                signupBody(emailTicket, phoneTicket, password, name, terms)))
        .andReturn();
  }

  @Test
  void 이메일_가입은_계정과_세션을_만들고_접근_상태_판정을_돌려준다() throws Exception {
    String emailTicket = flow.sendAndVerifyEmail(EMAIL);
    String phoneTicket = flow.sendAndVerifyPhone(PHONE);

    MvcResult result =
        mockMvc
            .perform(
                jsonPost(
                    "/api/auth/signup/email",
                    signupBody(emailTicket, phoneTicket, PASSWORD, "홍길동", requiredTerms())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessCondition").value("PRE_VERIFICATION"))
            .andExpect(jsonPath("$.officeId").doesNotExist())
            .andExpect(jsonPath("$.pendingRegistrationId").doesNotExist())
            .andExpect(jsonPath("$.removalReason").doesNotExist())
            .andReturn();

    Cookie session = AuthFlowSupport.sessionCookie(result);
    assertThat(session).isNotNull();
    mockMvc
        .perform(get("/api/auth/context").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessCondition").value("PRE_VERIFICATION"))
        .andExpect(jsonPath("$.reconsentRequired").value(false));
    mockMvc
        .perform(get("/api/me").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("홍길동"))
        .andExpect(jsonPath("$.email").value(EMAIL))
        .andExpect(jsonPath("$.emailVerified").value(true))
        .andExpect(jsonPath("$.phone").value(PHONE))
        .andExpect(jsonPath("$.phoneVerified").value(true))
        .andExpect(jsonPath("$.loginMethods[0]").value("EMAIL"))
        .andExpect(jsonPath("$.kakaoLinked").value(false));
  }

  @Test
  void 가입한_계정에는_티켓의_번호와_확인_시각을_저장하고_비밀번호는_해시로만_둔다() throws Exception {
    signup(PASSWORD, "홍길동", requiredTerms());

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT email, phone, phone_verified_at, email_verified_at, password_hash, name,"
                + " account_status FROM app_user");
    assertThat(row.get("email")).isEqualTo(EMAIL);
    assertThat(row.get("phone")).isEqualTo(PHONE);
    assertThat(row.get("phone_verified_at")).isNotNull();
    assertThat(row.get("email_verified_at")).isNotNull();
    assertThat((String) row.get("password_hash")).startsWith("$2").doesNotContain(PASSWORD);
    assertThat(row.get("account_status")).isEqualTo("ACTIVE");
  }

  @Test
  void 가입하면_약관_동의를_가입_경로와_시행_버전으로_기록한다() throws Exception {
    signup(PASSWORD, "홍길동", requiredTerms());

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT terms_code, terms_version, agreed, consent_path FROM terms_agreement");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsEntry("terms_code", "TOS")
        .containsEntry("terms_version", "2026-09-01")
        .containsEntry("agreed", true)
        .containsEntry("consent_path", "SIGNUP_EMAIL");
    Integer preferences =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app_user_notification_preference", Integer.class);
    assertThat(preferences).isZero();
  }

  @Test
  void 선택_약관을_보내면_수신_설정을_켜고_결과_통지를_등록한다() throws Exception {
    signup(PASSWORD, "홍길동", allTerms());

    Boolean enabled =
        jdbcTemplate.queryForObject(
            "SELECT enabled FROM app_user_notification_preference WHERE channel_key = 'MARKETING'",
            Boolean.class);
    assertThat(enabled).isTrue();
    Map<String, Object> notice =
        jdbcTemplate.queryForMap(
            "SELECT notice_type, status, cycle_no, (due_at - expressed_at) AS due FROM"
                + " consent_notice_delivery");
    assertThat(notice)
        .containsEntry("notice_type", "MARKETING_RESULT")
        .containsEntry("status", "PENDING");
    assertThat(notice.get("cycle_no")).isEqualTo(1);
    assertThat(notice.get("due").toString()).contains("14 days");
    Integer agreements =
        jdbcTemplate.queryForObject("SELECT count(*) FROM terms_agreement", Integer.class);
    assertThat(agreements).isEqualTo(2);
  }

  @Test
  void 제출_버전이_시행_버전과_다르면_409이고_계정과_동의_기록을_만들지_않으며_티켓은_다시_쓸_수_있다() throws Exception {
    String emailTicket = flow.sendAndVerifyEmail(EMAIL);
    String phoneTicket = flow.sendAndVerifyPhone(PHONE);
    String oldVersion = "[{\"termsCode\":\"TOS\",\"version\":\"2026-01-01\"}]";

    assertError(
            mockMvc.perform(
                jsonPost(
                    "/api/auth/signup/email",
                    signupBody(emailTicket, phoneTicket, PASSWORD, "홍길동", oldVersion))),
            ErrorCode.TERMS_VERSION_CHANGED)
        .andExpect(jsonPath("$.changedItems[0].termsCode").value("TOS"))
        .andExpect(jsonPath("$.changedItems[0].submittedVersion").value("2026-01-01"))
        .andExpect(jsonPath("$.changedItems[0].currentVersion").value("2026-09-01"))
        .andExpect(jsonPath("$.changedItems[0].documentUrl").value("/legal/terms/TOS/2026-09-01"))
        .andExpect(jsonPath("$.changedItems[0].changeSummary").isEmpty());
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class))
        .isZero();
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM terms_agreement", Integer.class))
        .isZero();

    mockMvc
        .perform(
            jsonPost(
                "/api/auth/signup/email",
                signupBody(emailTicket, phoneTicket, PASSWORD, "홍길동", requiredTerms())))
        .andExpect(status().isCreated());
  }

  @Test
  void 필수_약관이_빠지면_INVALID_REQUEST다() throws Exception {
    assertFieldError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/signup/email",
                signupBody(
                    flow.sendAndVerifyEmail(EMAIL),
                    flow.sendAndVerifyPhone(PHONE),
                    PASSWORD,
                    "홍길동",
                    "[{\"termsCode\":\"MARKETING\",\"version\":\"2026-09-01\"}]"))),
        "agreedTerms",
        "REQUIRED");
  }

  @Test
  void 열람_고지_항목을_동의_목록에_넣으면_INVALID_REQUEST다() throws Exception {
    String terms =
        "[{\"termsCode\":\"TOS\",\"version\":\"2026-09-01\"},"
            + "{\"termsCode\":\"PRIVACY\",\"version\":\"2026-09-01\"}]";

    assertFieldError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/signup/email",
                signupBody(
                    flow.sendAndVerifyEmail(EMAIL),
                    flow.sendAndVerifyPhone(PHONE),
                    PASSWORD,
                    "홍길동",
                    terms))),
        "agreedTerms",
        "NOT_ALLOWED");
  }

  @Test
  void 같은_약관을_두_번_보내면_INVALID_REQUEST다() throws Exception {
    String terms =
        "[{\"termsCode\":\"TOS\",\"version\":\"2026-09-01\"},"
            + "{\"termsCode\":\"TOS\",\"version\":\"2026-09-01\"}]";

    assertFieldError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/signup/email",
                signupBody(
                    flow.sendAndVerifyEmail(EMAIL),
                    flow.sendAndVerifyPhone(PHONE),
                    PASSWORD,
                    "홍길동",
                    terms))),
        "agreedTerms",
        "NOT_ALLOWED");
  }

  @Test
  void 티켓이_하나라도_비어_있으면_INVALID_REQUEST이고_계정을_만들지_않는다() throws Exception {
    assertFieldError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/signup/email",
                signupBody("", flow.sendAndVerifyPhone(PHONE), PASSWORD, "홍길동", requiredTerms()))),
        "verifiedEmailTicket",
        "REQUIRED");
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class))
        .isZero();
  }

  @Test
  void 위조한_티켓은_TOKEN_INVALID다() throws Exception {
    String phoneTicket = flow.sendAndVerifyPhone(PHONE);

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/signup/email",
                signupBody("forged.ticket", phoneTicket, PASSWORD, "홍길동", requiredTerms()))),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 만료된_티켓은_TOKEN_EXPIRED다() throws Exception {
    String emailTicket = flow.sendAndVerifyEmail(EMAIL);
    String phoneTicket = flow.sendAndVerifyPhone(PHONE);
    String expired =
        ticketService
            .issue(TicketType.PHONE, PHONE, "SIGNUP", null, Duration.ofSeconds(-5))
            .token();

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/signup/email",
                signupBody(emailTicket, expired, PASSWORD, "홍길동", requiredTerms()))),
        ErrorCode.TOKEN_EXPIRED);
  }

  @Test
  void 티켓은_한_번만_쓸_수_있다() throws Exception {
    String emailTicket = flow.sendAndVerifyEmail(EMAIL);
    String phoneTicket = flow.sendAndVerifyPhone(PHONE);
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/signup/email",
                signupBody(emailTicket, phoneTicket, PASSWORD, "홍길동", requiredTerms())))
        .andExpect(status().isCreated());

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/signup/email",
                signupBody(emailTicket, phoneTicket, PASSWORD, "홍길동", requiredTerms()))),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 이미_본인확인된_번호로_가입하면_409이고_티켓을_소진하지_않는다() throws Exception {
    saveEmailUser("other@example.com", PHONE);
    String emailTicket = flow.sendAndVerifyEmail(EMAIL);
    String phoneTicket = flow.sendAndVerifyPhone(PHONE);

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/signup/email",
                signupBody(emailTicket, phoneTicket, PASSWORD, "홍길동", requiredTerms()))),
        ErrorCode.PHONE_ALREADY_REGISTERED);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void 비밀번호는_코드_포인트_기준_8자에서_12자까지_허용한다() throws Exception {
    assertFieldError(signupWithPassword("abcdef7"), "password", "TOO_SHORT");
    assertFieldError(signupWithPassword("abcdefghijklm"), "password", "TOO_LONG");
    signupWithPassword("abcdefg8").andExpect(status().isCreated());
  }

  private ResultActions signupWithPassword(String password) throws Exception {
    String email = "u" + Math.abs(password.hashCode()) + "@example.com";
    String phone = "010-7" + String.format("%03d", Math.abs(password.hashCode()) % 1000) + "-1111";
    return mockMvc.perform(
        jsonPost(
            "/api/auth/signup/email",
            signupBody(
                flow.sendAndVerifyEmail(email),
                flow.sendAndVerifyPhone(phone),
                password,
                "홍길동",
                requiredTerms())));
  }

  @Test
  void 이모지도_한_글자로_세어_12자까지_허용하고_13자는_거절한다() throws Exception {
    String twelve = "😀".repeat(12);
    String thirteen = "😀".repeat(13);
    String eight = "😀".repeat(8);
    String seven = "😀".repeat(7);

    assertFieldError(signupWithPassword(seven), "password", "TOO_SHORT");
    assertFieldError(signupWithPassword(thirteen), "password", "TOO_LONG");
    signupWithPassword(eight).andExpect(status().isCreated());
    signupWithPassword(twelve).andExpect(status().isCreated());
  }

  @Test
  void 공백도_길이에_포함하고_앞뒤_공백을_그대로_보존한다() throws Exception {
    String spaced = "  abcdef  ";
    signupWithPassword(spaced).andExpect(status().isCreated());
    String email = "u" + Math.abs(spaced.hashCode()) + "@example.com";

    login(email, spaced);
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":" + quote(spaced) + "}"))
        .andExpect(status().isOk());
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":" + quote(spaced.strip()) + "}")),
        ErrorCode.INVALID_CREDENTIALS);
  }

  @Test
  void 대소문자를_바꾼_비밀번호는_다른_비밀번호다() throws Exception {
    signupWithPassword("AbCdEfGh1").andExpect(status().isCreated());
    String email = "u" + Math.abs("AbCdEfGh1".hashCode()) + "@example.com";

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"abcdefgh1\"}")),
        ErrorCode.INVALID_CREDENTIALS);
  }

  @Test
  void 흔한_비밀번호는_거절한다() throws Exception {
    assertFieldError(signupWithPassword("Password123"), "password", "NOT_ALLOWED");
    assertFieldError(signupWithPassword("12345678"), "password", "NOT_ALLOWED");
  }

  @Test
  void 표시_이름은_1자에서_50자이고_HTML을_허용하지_않는다() throws Exception {
    assertFieldError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/signup/email",
                signupBody(
                    flow.sendAndVerifyEmail(EMAIL),
                    flow.sendAndVerifyPhone(PHONE),
                    PASSWORD,
                    "가".repeat(51),
                    requiredTerms()))),
        "displayName",
        "TOO_LONG");
    assertFieldError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/signup/email",
                signupBody(
                    flow.sendAndVerifyEmail("b@example.com"),
                    flow.sendAndVerifyPhone("010-2222-3333"),
                    PASSWORD,
                    "<b>이름</b>",
                    requiredTerms()))),
        "displayName",
        "HTML_NOT_ALLOWED");
  }

  @Test
  void 가입_요청도_CSRF_검증을_거친다() throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/auth/signup/email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 가입_응답의_세션_쿠키는_보안_속성을_가진다() throws Exception {
    MvcResult result = signup(PASSWORD, "홍길동", requiredTerms());

    String setCookie =
        result.getResponse().getHeaders("Set-Cookie").stream()
            .filter(header -> header.startsWith("__Host-sairo_session="))
            .findFirst()
            .orElseThrow();
    assertThat(setCookie)
        .contains("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Lax")
        .contains("Path=/");
    assertThat(setCookie).doesNotContain("Domain=");
  }
}
