package com.sairo.be.domain.auth.service;

import com.sairo.be.global.port.KakaoUnlinkPort;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Profile("prod")
@Component
public class KakaoUnlinkClient implements KakaoUnlinkPort {

  private static final String UNLINK_URI = "https://kapi.kakao.com/v1/user/unlink";

  private final RestClient restClient;
  private final KakaoProperties properties;

  public KakaoUnlinkClient(KakaoProperties properties, RestClient.Builder restClientBuilder) {
    this.restClient = restClientBuilder.build();
    this.properties = properties;
  }

  @Override
  public void unlink(String kakaoProviderKey) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("target_id_type", "user_id");
    form.add("target_id", kakaoProviderKey);
    try {
      restClient
          .post()
          .uri(UNLINK_URI)
          .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.adminKey())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      if (!isAlreadyUnlinked(e)) {
        throw e;
      }
    }
  }

  private boolean isAlreadyUnlinked(RestClientResponseException e) {
    String body = e.getResponseBodyAsString();
    return e.getStatusCode().value() == 400 && (body.contains("-101") || body.contains("-102"));
  }
}
