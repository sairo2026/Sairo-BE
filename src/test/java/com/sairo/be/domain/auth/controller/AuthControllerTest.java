package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.AuthFlowSupport.KakaoStart;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.AppUserBuilder;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class AuthControllerTest extends AuthIntegrationTestBase {

  private static final String FRONT = "http://localhost:3000";
  private static final String FAILED = FRONT + "/login?error=auth_failed";

  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;

  private String callback(KakaoStart start, String code) throws Exception {
    return mockMvc
        .perform(
            get("/api/auth/kakao/callback")
                .cookie(start.cookie())
                .param("code", code)
                .param("state", start.state()))
        .andReturn()
        .getResponse()
        .getHeader("Location");
  }

  private MvcResult callbackResult(KakaoStart start, String code) throws Exception {
    return mockMvc
        .perform(
            get("/api/auth/kakao/callback")
                .cookie(start.cookie())
                .param("code", code)
                .param("state", start.state()))
        .andReturn();
  }

  @Test
  void start는_카카오_인가_URL로_리다이렉트하고_세션과_state를_만든다() throws Exception {
    KakaoStart start = flow.startKakao("/api/auth/kakao/start", null);

    assertThat(start.location()).startsWith("https://kauth.stub.invalid/oauth/authorize?state=");
    assertThat(start.cookie()).isNotNull();
    assertThat(redisTemplate.keys("sairo:oauth-state:*")).hasSize(1);
  }

  @Test
  void 허용되지_않는_purpose는_400이다() throws Exception {
    assertFieldError(
        mockMvc.perform(get("/api/auth/kakao/start").param("purpose", "OTHER")),
        "purpose",
        "NOT_ALLOWED");
  }

  @Test
  void 신규_카카오_사용자는_가입_대기_계정과_세션을_만들고_가입_화면으로_이동한다() throws Exception {
    KakaoStart start = flow.startKakao("/api/auth/kakao/start", null);

    MvcResult result = callbackResult(start, "5001001");

    assertThat(result.getResponse().getHeader("Location")).isEqualTo(FRONT + "/signup/kakao");
    Cookie authenticated = AuthFlowSupport.sessionCookie(result);
    assertThat(authenticated).isNotNull();
    assertThat(authenticated.getValue()).isNotEqualTo(start.cookie().getValue());
    Map<String, Object> user =
        jdbcTemplate.queryForMap(
            "SELECT kakao_provider_key, phone_verified_at, email FROM app_user");
    assertThat(user.get("kakao_provider_key")).isEqualTo("5001001");
    assertThat(user.get("phone_verified_at")).isNull();
    assertThat(user.get("email")).isNull();
    assertThat(jdbcTemplate.queryForObject("SELECT auth_method FROM login_history", String.class))
        .isEqualTo("KAKAO");
    mockMvc.perform(get("/api/auth/context").cookie(authenticated)).andExpect(status().isOk());
  }

  @Test
  void 가입을_마친_사용자는_접근_판정에_따른_화면으로_이동한다() throws Exception {
    AppUser user =
        appUserRepository.saveAndFlush(
            AppUserBuilder.anAppUser().kakaoProviderKey("5001002").build());
    jdbcTemplate.update("UPDATE app_user SET phone = '010-1111-2222', phone_verified_at = now()");
    KakaoStart start = flow.startKakao("/api/auth/kakao/start", null);

    assertThat(callback(start, "5001002")).isEqualTo(FRONT + "/office/start");
    assertThat(user.getId()).isNotNull();
  }

  @Test
  void 승인된_소속과_정상_구독이면_홈으로_이동하고_인증_세션은_보안_쿠키다() throws Exception {
    TestData testData =
        new TestData(appUserRepository, officeRepository, officeMembershipRepository);
    TestData.Staff staff = testData.approvedStaff(OfficeMembershipRole.AGENT);
    jdbcTemplate.update(
        "UPDATE app_user SET kakao_provider_key = '5001003', phone = '010-1111-3333',"
            + " phone_verified_at = now() WHERE id = ?",
        staff.user().getId());
    jdbcTemplate.update(
        "INSERT INTO subscription (office_id, plan, status) VALUES (?, 'SOLO', 'ACTIVE')",
        staff.office().getId());
    KakaoStart start = flow.startKakao("/api/auth/kakao/start", null);

    MvcResult result = callbackResult(start, "5001003");

    assertThat(result.getResponse().getHeader("Location")).isEqualTo(FRONT + "/");
    String sessionHeader =
        result.getResponse().getHeaders("Set-Cookie").stream()
            .filter(h -> h.startsWith(AuthFlowSupport.SESSION_COOKIE_NAME + "="))
            .findFirst()
            .orElseThrow();
    assertThat(sessionHeader)
        .contains("HttpOnly")
        .contains("Secure")
        .containsIgnoringCase("SameSite=Lax");
    mockMvc
        .perform(get("/api/auth/context").cookie(AuthFlowSupport.sessionCookie(result)))
        .andExpect(status().isOk());
  }

  @Test
  void state가_일치하지_않으면_로그인_화면으로_이동하고_계정을_만들지_않는다() throws Exception {
    KakaoStart start = flow.startKakao("/api/auth/kakao/start", null);

    String location =
        mockMvc
            .perform(
                get("/api/auth/kakao/callback")
                    .cookie(start.cookie())
                    .param("code", "5001001")
                    .param("state", "wrong-state"))
            .andReturn()
            .getResponse()
            .getHeader("Location");

    assertThat(location).isEqualTo(FAILED);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class))
        .isZero();
  }

  @Test
  void 세션이_없는_콜백은_로그인_화면으로_이동한다() throws Exception {
    mockMvc
        .perform(get("/api/auth/kakao/callback").param("code", "5001001").param("state", "x"))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string("Location", FAILED));
  }

  @Test
  void 인증_코드가_거절되면_로그인_화면으로_이동한다() throws Exception {
    KakaoStart start = flow.startKakao("/api/auth/kakao/start", null);

    assertThat(callback(start, "not-a-number")).isEqualTo(FAILED);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class))
        .isZero();
  }

  @Test
  void 실패_콜백도_state를_소비해_정상_코드로_재사용할_수_없다() throws Exception {
    KakaoStart start = flow.startKakao("/api/auth/kakao/start", null);

    String failed =
        mockMvc
            .perform(
                get("/api/auth/kakao/callback")
                    .cookie(start.cookie())
                    .param("state", start.state())
                    .param("error", "access_denied")
                    .param("code", "5001001"))
            .andReturn()
            .getResponse()
            .getHeader("Location");

    assertThat(failed).isEqualTo(FAILED);
    assertThat(callback(start, "5001001")).isEqualTo(FAILED);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class))
        .isZero();
  }

  @Test
  void 성공한_콜백의_state도_다시_쓸_수_없다() throws Exception {
    KakaoStart start = flow.startKakao("/api/auth/kakao/start", null);
    callback(start, "5001001");

    assertThat(callback(start, "5001001")).isEqualTo(FAILED);
  }

  @Test
  void 다른_세션의_state는_쓸_수_없다() throws Exception {
    KakaoStart first = flow.startKakao("/api/auth/kakao/start", null);
    KakaoStart second = flow.startKakao("/api/auth/kakao/start", null);

    String location =
        mockMvc
            .perform(
                get("/api/auth/kakao/callback")
                    .cookie(second.cookie())
                    .param("code", "5001001")
                    .param("state", first.state()))
            .andReturn()
            .getResponse()
            .getHeader("Location");

    assertThat(location).isEqualTo(FAILED);
  }

  @Test
  void 재인증_용도로_시작한_state는_로그인_콜백에서_쓸_수_없다() throws Exception {
    AppUser user =
        appUserRepository.saveAndFlush(
            AppUserBuilder.anAppUser().kakaoProviderKey("5001004").build());
    Cookie session = kakaoSessionFor(user);
    KakaoStart stepUp = flow.startKakao("/api/auth/step-up/kakao/start", session);

    assertThat(stepUp.location()).contains("flow=STEP_UP");
    assertThat(callback(stepUp, "5001004")).isEqualTo(FAILED);
  }

  @Test
  void 탈퇴한_사용자는_로그인할_수_없다() throws Exception {
    appUserRepository.saveAndFlush(AppUserBuilder.anAppUser().kakaoProviderKey("5001005").build());
    jdbcTemplate.update("UPDATE app_user SET account_status = 'WITHDRAWN', withdrawn_at = now()");
    KakaoStart start = flow.startKakao("/api/auth/kakao/start", null);

    assertThat(callback(start, "5001005")).isEqualTo(FAILED);
  }

  @Test
  void 카카오_쪽에서_끊긴_계정은_다시_인증하면_같은_계정에_다시_연결한다() throws Exception {
    appUserRepository.saveAndFlush(AppUserBuilder.anAppUser().kakaoProviderKey("5001006").build());
    jdbcTemplate.update(
        "UPDATE app_user SET phone = '010-1111-4444', phone_verified_at = now(),"
            + " kakao_unlinked_at = now()");
    KakaoStart start = flow.startKakao("/api/auth/kakao/start", null);

    callback(start, "5001006");

    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT kakao_unlinked_at FROM app_user", java.sql.Timestamp.class))
        .isNull();
  }

  @Test
  void 반환_보관_권한자_용도는_세션_없이_인증_완료_코드만_전달한다() throws Exception {
    KakaoStart start = flow.startKakao("/api/auth/kakao/start?purpose=GRANT", null);

    MvcResult result = callbackResult(start, "5001007");

    String location = result.getResponse().getHeader("Location");
    assertThat(location).startsWith(FRONT + "/grant/verify?credential=");
    String credential = location.substring(location.indexOf("credential=") + 11);
    assertThat(redisTemplate.opsForValue().get("sairo:grant-credential:" + credential))
        .isEqualTo("5001007");
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class))
        .isZero();
    assertThat(
            result.getResponse().getHeaders("Set-Cookie").stream()
                .anyMatch(h -> h.startsWith(AuthFlowSupport.SESSION_COOKIE_NAME + "=")))
        .isFalse();
  }

  @Test
  void 동시에_같은_state로_콜백해도_한_요청만_성공한다() throws Exception {
    KakaoStart start = flow.startKakao("/api/auth/kakao/start", null);
    CountDownLatch ready = new CountDownLatch(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                ready.await(10, TimeUnit.SECONDS);
                return callback(start, "5001008");
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                ready.await(10, TimeUnit.SECONDS);
                return callback(start, "5001008");
              });
      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(FRONT + "/signup/kakao", FAILED);
    }
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void 인증되지_않은_요청은_401이다() throws Exception {
    mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void 콜백에서_인증_세션이_발급되면_이전_세션은_더_이상_쓸_수_없다() throws Exception {
    KakaoStart start = flow.startKakao("/api/auth/kakao/start", null);
    callback(start, "5001009");

    mockMvc
        .perform(get("/api/auth/context").cookie(start.cookie()))
        .andExpect(status().isUnauthorized());
  }
}
