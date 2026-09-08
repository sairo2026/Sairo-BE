package com.sairo.be.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
}
