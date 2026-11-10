package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.service.KakaoLoginService;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.AppUserBuilder;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@IntegrationTest
class GrantAuthIntegrationTest extends GrantTestBase {

  private static final String RETURN = "/api/grant-auth/return";
  private static final String ARCHIVE = "/api/grant-auth/archive";

  @Test
  void 이메일_재인증이_성공하면_반환_전용_세션을_발급하고_권한_정보를_준다() throws Exception {
    Holder holder = emailHolder();
    long grantId = returnGrant(holder);
    var result =
        mockMvc
            .perform(postRequest(RETURN, grantAuthBody(grantId, "EMAIL", PASSWORD)).apply(null))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.grantId").value(grantId))
            .andExpect(jsonPath("$.grantType").value("RETURN"))
            .andExpect(jsonPath("$.expiresAt").exists())
            .andReturn();
    Cookie cookie = com.sairo.be.support.AuthFlowSupport.sessionCookie(result);
    assertThat(cookie).isNotNull();
    mockMvc
        .perform(getRequest("/api/return-grants/current").apply(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grantId").value(grantId))
        .andExpect(jsonPath("$.revoked").value(false));
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM login_history", Integer.class))
        .isZero();
  }

  @Test
  void 탈퇴한_계정도_권한_행의_대상자와_인증_주체가_같으면_허용한다() throws Exception {
    Holder holder = emailHolder();
    long grantId = returnGrant(holder);
    jdbcTemplate.update(
        "UPDATE app_user SET account_status = 'WITHDRAWN', withdrawn_at = now() WHERE id = ?",
        holder.user().getId());
    mockMvc
        .perform(postRequest(RETURN, grantAuthBody(grantId, "EMAIL", PASSWORD)).apply(null))
        .andExpect(status().isOk());
  }

  @Test
  void 비밀번호가_틀리면_401_INVALID_CREDENTIALS이고_세션을_만들지_않는다() throws Exception {
    Holder holder = emailHolder();
    long grantId = returnGrant(holder);
    assertError(
        mockMvc.perform(
            postRequest(RETURN, grantAuthBody(grantId, "EMAIL", "wrong-password")).apply(null)),
        ErrorCode.INVALID_CREDENTIALS);
  }

  @Test
  void 카카오_재인증은_인증_완료_코드를_한_번만_소비하고_같은_사용자일_때만_허용한다() throws Exception {
    TestDataKakao kakao = kakaoHolder("kakao-key-holder");
    redisTemplate
        .opsForValue()
        .set(
            KakaoLoginService.GRANT_CREDENTIAL_PREFIX + "code-1",
            "kakao-key-holder",
            Duration.ofMinutes(5));
    mockMvc
        .perform(postRequest(RETURN, grantAuthBody(kakao.grantId(), "KAKAO", "code-1")).apply(null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grantType").value("RETURN"));
    assertError(
        mockMvc.perform(
            postRequest(RETURN, grantAuthBody(kakao.grantId(), "KAKAO", "code-1")).apply(null)),
        ErrorCode.INVALID_CREDENTIALS);
  }

  @Test
  void 권한_대상자와_다른_카카오_계정이면_403_STEP_UP_ACCOUNT_MISMATCH이다() throws Exception {
    TestDataKakao kakao = kakaoHolder("kakao-key-holder");
    redisTemplate
        .opsForValue()
        .set(
            KakaoLoginService.GRANT_CREDENTIAL_PREFIX + "code-2",
            "another-kakao-key",
            Duration.ofMinutes(5));
    assertError(
        mockMvc.perform(
            postRequest(RETURN, grantAuthBody(kakao.grantId(), "KAKAO", "code-2")).apply(null)),
        ErrorCode.STEP_UP_ACCOUNT_MISMATCH);
    assertError(
        mockMvc.perform(
            postRequest(RETURN, grantAuthBody(kakao.grantId(), "KAKAO", "never-issued"))
                .apply(null)),
        ErrorCode.INVALID_CREDENTIALS);
  }

  @Test
  void 만료되었거나_회수된_반환권한은_403_RETURN_GRANT_INVALID이다() throws Exception {
    Holder expiredHolder = emailHolder();
    long expired = returnGrant(expiredHolder);
    expireReturnGrant(expired);
    assertError(
        mockMvc.perform(postRequest(RETURN, grantAuthBody(expired, "EMAIL", PASSWORD)).apply(null)),
        ErrorCode.RETURN_GRANT_INVALID);
    Holder revokedHolder = emailHolder();
    long revoked = returnGrant(revokedHolder);
    jdbcTemplate.update(
        "UPDATE return_grant SET revoked_at = now(), revoked_reason = '회수' WHERE id = ?", revoked);
    assertError(
        mockMvc.perform(postRequest(RETURN, grantAuthBody(revoked, "EMAIL", PASSWORD)).apply(null)),
        ErrorCode.RETURN_GRANT_INVALID);
  }

  @Test
  void 만료되었거나_종료된_보관권한은_403_ARCHIVE_GRANT_INVALID이다() throws Exception {
    Holder holder = emailHolder();
    long officeId = holder.former().office().getId();
    long terminated = archiveGrant(officeId, holder.user().getId());
    jdbcTemplate.update(
        "UPDATE archive_grant SET terminated_at = now(), terminated_reason = '종료' WHERE id = ?",
        terminated);
    assertError(
        mockMvc.perform(
            postRequest(ARCHIVE, grantAuthBody(terminated, "EMAIL", PASSWORD)).apply(null)),
        ErrorCode.ARCHIVE_GRANT_INVALID);
    long expired = archiveGrant(officeId, holder.user().getId());
    jdbcTemplate.update(
        "UPDATE archive_grant SET expires_at = now() - interval '1 day' WHERE id = ?", expired);
    assertError(
        mockMvc.perform(
            postRequest(ARCHIVE, grantAuthBody(expired, "EMAIL", PASSWORD)).apply(null)),
        ErrorCode.ARCHIVE_GRANT_INVALID);
  }

  @Test
  void 보관권한은_계약상_무기한이면_만료_시각이_null이다() throws Exception {
    Holder holder = emailHolder();
    long grantId = archiveGrant(holder.former().office().getId(), holder.user().getId());
    mockMvc
        .perform(postRequest(ARCHIVE, grantAuthBody(grantId, "EMAIL", PASSWORD)).apply(null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grantType").value("ARCHIVE"))
        .andExpect(jsonPath("$.expiresAt").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void 없는_권한이거나_다른_종류의_권한_ID는_404_RESOURCE_NOT_FOUND이다() throws Exception {
    Holder holder = emailHolder();
    long returnId = returnGrant(holder);
    assertError(
        mockMvc.perform(postRequest(RETURN, grantAuthBody(999_999, "EMAIL", PASSWORD)).apply(null)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(
            postRequest(ARCHIVE, grantAuthBody(returnId, "EMAIL", PASSWORD)).apply(null)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 필수_필드가_없거나_인증_방식이_허용값이_아니면_400이다() throws Exception {
    assertError(
        mockMvc.perform(postRequest(RETURN, "{\"grantId\":1}").apply(null)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(postRequest(RETURN, grantAuthBody(1, "SMS", "x")).apply(null)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            postRequest(ARCHIVE, "{\"grantId\":1,\"authMethod\":\"EMAIL\"}").apply(null)),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 공개_엔드포인트_요청_제한을_넘으면_429_RATE_LIMITED이다() throws Exception {
    for (int i = 0; i < 30; i++) {
      mockMvc.perform(postRequest(RETURN, grantAuthBody(999_999, "EMAIL", "x")).apply(null));
    }
    assertError(
            mockMvc.perform(postRequest(RETURN, grantAuthBody(999_999, "EMAIL", "x")).apply(null)),
            ErrorCode.RATE_LIMITED)
        .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
  }

  @Test
  void 반환_전용_세션은_일반_업무_API에_접근할_수_없다() throws Exception {
    Holder holder = emailHolder();
    long grantId = returnGrant(holder);
    Cookie cookie = grantSession(RETURN, grantId, "EMAIL", PASSWORD);
    assertError(
        mockMvc.perform(getRequest("/api/billing/plans").apply(cookie)), ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(getRequest("/api/offices/current").apply(cookie)), ErrorCode.FORBIDDEN);
  }

  private record TestDataKakao(long grantId) {}

  private TestDataKakao kakaoHolder(String providerKey) {
    var owner = owner();
    AppUser user =
        appUserRepository.saveAndFlush(
            AppUserBuilder.anAppUser().kakaoProviderKey(providerKey).build());
    var former =
        data()
            .approvedStaffIn(
                owner.office(), user, com.sairo.be.domain.office.entity.OfficeMembershipRole.AGENT);
    long grantId =
        returnGrantService.issue(former.office().getId(), user.getId(), Instant.now()).getId();
    return new TestDataKakao(grantId);
  }
}
