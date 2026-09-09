package com.sairo.be.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

@ExtendWith(OutputCaptureExtension.class)
class AuthServiceTest {

  @Test
  void 외부_오류의_본문과_스택은_로그에_남기지_않는다(CapturedOutput output) {
    var client = mock(KakaoOAuthClient.class);
    var principals = mock(StaffPrincipalQueryService.class);
    when(client.exchangeToken("test-code"))
        .thenThrow(new RestClientException("sensitive-response-marker"));

    assertThat(new AuthService(client, principals).authenticate("test-code")).isEmpty();
    assertThat(output.getAll())
        .contains("카카오 인증 요청이 실패했습니다.")
        .doesNotContain("sensitive-response-marker", "RestClientException");
    verifyNoInteractions(principals);
  }

  @Test
  void 카카오_응답_실패는_상태코드만_로그에_남기고_본문은_남기지_않는다(CapturedOutput output) {
    var client = mock(KakaoOAuthClient.class);
    var principals = mock(StaffPrincipalQueryService.class);
    when(client.exchangeToken("test-code"))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                HttpHeaders.EMPTY,
                "sensitive-response-marker".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));

    assertThat(new AuthService(client, principals).authenticate("test-code")).isEmpty();
    assertThat(output.getAll())
        .contains("카카오 인증 요청이 실패했습니다.")
        .contains("400")
        .doesNotContain("sensitive-response-marker");
    verifyNoInteractions(principals);
  }
}
