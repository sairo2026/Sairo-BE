package com.sairo.be.domain.auth.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Profile("!migrate")
@Component
public class KakaoOAuthClient {

  private static final String AUTHORIZE_URI = "https://kauth.kakao.com/oauth/authorize";
  private static final String TOKEN_URI = "https://kauth.kakao.com/oauth/token";
  private static final String USER_INFO_URI = "https://kapi.kakao.com/v2/user/me";

  private final RestClient restClient;
  private final KakaoProperties properties;

  public KakaoOAuthClient(KakaoProperties properties) {
    this.restClient = RestClient.create();
    this.properties = properties;
  }

  public String buildAuthorizeUrl(String state) {
    return UriComponentsBuilder.fromUriString(AUTHORIZE_URI)
        .queryParam("client_id", properties.restApiKey())
        .queryParam("redirect_uri", properties.redirectUri())
        .queryParam("response_type", "code")
        .queryParam("state", state)
        .build()
        .toUriString();
  }

  public String exchangeToken(String code) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("client_id", properties.restApiKey());
    form.add("client_secret", properties.clientSecret());
    form.add("redirect_uri", properties.redirectUri());
    form.add("code", code);

    TokenResponse response =
        restClient
            .post()
            .uri(TOKEN_URI)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(TokenResponse.class);

    if (response == null || response.accessToken() == null) {
      throw new RestClientException("카카오 토큰 응답에 access_token이 없습니다.");
    }
    return response.accessToken();
  }

  public Long fetchUserId(String accessToken) {
    UserInfoResponse response =
        restClient
            .get()
            .uri(USER_INFO_URI)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .retrieve()
            .body(UserInfoResponse.class);

    if (response == null || response.id() == null) {
      throw new RestClientException("카카오 사용자 정보 응답에 id가 없습니다.");
    }
    return response.id();
  }

  private record TokenResponse(@JsonProperty("access_token") String accessToken) {}

  private record UserInfoResponse(Long id) {}
}
