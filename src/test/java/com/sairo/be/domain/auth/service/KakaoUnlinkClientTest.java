package com.sairo.be.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class KakaoUnlinkClientTest {

  private static final String URI = "https://kapi.kakao.com/v1/user/unlink";

  private MockRestServiceServer server;
  private KakaoUnlinkClient client;

  private void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    client =
        new KakaoUnlinkClient(
            new KakaoProperties(
                "key", "secret", "https://x/cb", null, null, null, null, "admin-key", "1"),
            builder);
  }

  @Test
  void 어드민_키_헤더와_폼_본문으로_연결_끊기를_요청한다() {
    setUp();
    var form = new LinkedMultiValueMap<String, String>();
    form.add("target_id_type", "user_id");
    form.add("target_id", "12345");
    server
        .expect(requestTo(URI))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "KakaoAK admin-key"))
        .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
        .andExpect(content().formData(form))
        .andRespond(withSuccess("{\"id\":12345}", MediaType.APPLICATION_JSON));

    client.unlink("12345");

    server.verify();
  }

  @Test
  void 이미_끊긴_상태라는_응답은_성공으로_본다() {
    setUp();
    server
        .expect(requestTo(URI))
        .andRespond(
            withBadRequest().body("{\"code\":-102}").contentType(MediaType.APPLICATION_JSON));

    assertThatCode(() -> client.unlink("12345")).doesNotThrowAnyException();
  }

  @Test
  void 그_밖의_실패는_예외로_알린다() {
    setUp();
    server.expect(requestTo(URI)).andRespond(withServerError());

    assertThatThrownBy(() -> client.unlink("12345")).isInstanceOf(RestClientException.class);
  }

  @Test
  void 다른_400_응답은_예외로_알린다() {
    setUp();
    server
        .expect(requestTo(URI))
        .andRespond(
            withBadRequest().body("{\"code\":-401}").contentType(MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.unlink("12345")).isInstanceOf(RestClientException.class);
  }
}
