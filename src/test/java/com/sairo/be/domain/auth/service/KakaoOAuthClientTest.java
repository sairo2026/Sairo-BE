package com.sairo.be.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sairo.be.global.port.KakaoFlow;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class KakaoOAuthClientTest {

  @Test
  void 인가URL은_state와_등록한_리다이렉트를_포함하고_프로필_권한을_요청하지_않는다() {
    KakaoOAuthClient client = new KakaoOAuthClient(properties(), RestClient.builder());
    var query =
        UriComponentsBuilder.fromUriString(client.buildAuthorizeUrl("test-state"))
            .build()
            .getQueryParams();
    assertThat(query.getFirst("client_id")).isEqualTo("test-client");
    assertThat(query.getFirst("redirect_uri"))
        .isEqualTo("https://api.example.com/api/auth/kakao/callback");
    assertThat(query.getFirst("response_type")).isEqualTo("code");
    assertThat(query.getFirst("state")).isEqualTo("test-state");
    assertThat(query).doesNotContainKeys("scope", "client_secret");
  }

  @Test
  void 토큰교환은_폼에_Secret과_동일한_redirect_uri를_담고_회원조회는_Bearer를_사용한다() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    KakaoOAuthClient client = new KakaoOAuthClient(properties(), builder);
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "authorization_code");
    form.add("client_id", "test-client");
    form.add("client_secret", "test-secret");
    form.add("redirect_uri", "https://api.example.com/api/auth/kakao/callback");
    form.add("code", "test+code&value");
    server
        .expect(requestTo("https://kauth.kakao.com/oauth/token"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
        .andExpect(content().formData(form))
        .andRespond(withSuccess("{\"access_token\":\"test-token\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("https://kapi.kakao.com/v2/user/me"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer test-token"))
        .andRespond(withSuccess("{\"id\":5001001}", MediaType.APPLICATION_JSON));

    assertThat(client.fetchUserId(client.exchangeToken("test+code&value"))).isEqualTo(5001001L);
    server.verify();
  }

  @Test
  void 흐름마다_자기_리다이렉트를_쓰고_운영자_로그인과_초대_수락은_서로_다른_주소로_돌아온다() {
    KakaoOAuthClient client =
        new KakaoOAuthClient(
            new KakaoProperties(
                "test-client",
                "test-secret",
                "https://app.example.com/api/auth/kakao/callback",
                "https://app.example.com/api/auth/step-up/kakao/callback",
                "https://app.example.com/settings/login-methods/kakao-callback",
                "https://app.example.com/api/ops/auth/kakao/callback",
                "https://app.example.com/api/ops/auth/invitations/kakao/callback",
                null,
                null),
            RestClient.builder());

    assertThat(redirectUriOf(client, KakaoFlow.LOGIN))
        .isEqualTo("https://app.example.com/api/auth/kakao/callback");
    assertThat(redirectUriOf(client, KakaoFlow.STEP_UP))
        .isEqualTo("https://app.example.com/api/auth/step-up/kakao/callback");
    assertThat(redirectUriOf(client, KakaoFlow.LINK))
        .isEqualTo("https://app.example.com/settings/login-methods/kakao-callback");
    assertThat(redirectUriOf(client, KakaoFlow.OPERATOR))
        .isEqualTo("https://app.example.com/api/ops/auth/kakao/callback");
    assertThat(redirectUriOf(client, KakaoFlow.OPERATOR_INVITATION))
        .isEqualTo("https://app.example.com/api/ops/auth/invitations/kakao/callback");
  }

  @Test
  void 토큰교환은_인가_요청과_같은_흐름의_리다이렉트를_보낸다() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    KakaoOAuthClient client =
        new KakaoOAuthClient(
            new KakaoProperties(
                "test-client",
                "test-secret",
                "https://app.example.com/api/auth/kakao/callback",
                null,
                null,
                "https://app.example.com/api/ops/auth/kakao/callback",
                "https://app.example.com/api/ops/auth/invitations/kakao/callback",
                null,
                null),
            builder);
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "authorization_code");
    form.add("client_id", "test-client");
    form.add("client_secret", "test-secret");
    form.add("redirect_uri", "https://app.example.com/api/ops/auth/invitations/kakao/callback");
    form.add("code", "invitation-code");
    server
        .expect(requestTo("https://kauth.kakao.com/oauth/token"))
        .andExpect(content().formData(form))
        .andRespond(withSuccess("{\"access_token\":\"test-token\"}", MediaType.APPLICATION_JSON));

    assertThat(client.exchangeToken("invitation-code", KakaoFlow.OPERATOR_INVITATION))
        .isEqualTo("test-token");
    server.verify();
  }

  @Test
  void 흐름별_리다이렉트가_비어_있으면_로그인_리다이렉트를_쓴다() {
    KakaoOAuthClient client = new KakaoOAuthClient(properties(), RestClient.builder());

    assertThat(redirectUriOf(client, KakaoFlow.OPERATOR))
        .isEqualTo("https://api.example.com/api/auth/kakao/callback");
    assertThat(redirectUriOf(client, KakaoFlow.OPERATOR_INVITATION))
        .isEqualTo("https://api.example.com/api/auth/kakao/callback");
  }

  private KakaoProperties properties() {
    return new KakaoProperties(
        "test-client",
        "test-secret",
        "https://api.example.com/api/auth/kakao/callback",
        "https://api.example.com/api/auth/step-up/kakao/callback",
        null,
        null,
        null,
        null,
        null);
  }

  private String redirectUriOf(KakaoOAuthClient client, KakaoFlow flow) {
    return UriComponentsBuilder.fromUriString(client.buildAuthorizeUrl("test-state", flow))
        .build()
        .getQueryParams()
        .getFirst("redirect_uri");
  }
}
