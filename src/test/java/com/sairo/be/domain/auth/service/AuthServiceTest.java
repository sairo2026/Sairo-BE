package com.sairo.be.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sairo.be.global.security.StaffPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
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

    var autoApproval = mock(PilotStaffAutoApprovalService.class);
    assertThat(new AuthService(client, principals, autoApproval, false).authenticate("test-code"))
        .isEmpty();
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

    var autoApproval = mock(PilotStaffAutoApprovalService.class);
    assertThat(new AuthService(client, principals, autoApproval, false).authenticate("test-code"))
        .isEmpty();
    assertThat(output.getAll())
        .contains("카카오 인증 요청이 실패했습니다.")
        .contains("400")
        .doesNotContain("sensitive-response-marker");
    verifyNoInteractions(principals);
  }

  @Test
  void 자동승인이_꺼져있으면_미등록_직원은_자동승인을_시도하지_않는다() {
    var client = mock(KakaoOAuthClient.class);
    var principals = mock(StaffPrincipalQueryService.class);
    var autoApproval = mock(PilotStaffAutoApprovalService.class);
    when(client.exchangeToken("test-code")).thenReturn("token");
    when(client.fetchUserId("token")).thenReturn(1001L);
    when(principals.findByKakaoUserId(1001L)).thenReturn(Optional.empty());

    assertThat(new AuthService(client, principals, autoApproval, false).authenticate("test-code"))
        .isEmpty();
    verifyNoInteractions(autoApproval);
  }

  @Test
  void 자동승인이_켜져있으면_미등록_직원을_자동승인한다() {
    var client = mock(KakaoOAuthClient.class);
    var principals = mock(StaffPrincipalQueryService.class);
    var autoApproval = mock(PilotStaffAutoApprovalService.class);
    var approved = new StaffPrincipal(1L, 2L, 3L, "파일럿 사용자");
    when(client.exchangeToken("test-code")).thenReturn("token");
    when(client.fetchUserId("token")).thenReturn(1001L);
    when(principals.findByKakaoUserId(1001L)).thenReturn(Optional.empty());
    when(autoApproval.autoApprove(1001L)).thenReturn(Optional.of(approved));

    assertThat(new AuthService(client, principals, autoApproval, true).authenticate("test-code"))
        .contains(approved);
  }

  @Test
  void 자동승인이_켜져있어도_이미_등록된_직원이_있으면_자동승인을_시도하지_않는다() {
    var client = mock(KakaoOAuthClient.class);
    var principals = mock(StaffPrincipalQueryService.class);
    var autoApproval = mock(PilotStaffAutoApprovalService.class);
    var existing = new StaffPrincipal(1L, 2L, 3L, "박직원");
    when(client.exchangeToken("test-code")).thenReturn("token");
    when(client.fetchUserId("token")).thenReturn(1001L);
    when(principals.findByKakaoUserId(1001L)).thenReturn(Optional.of(existing));

    assertThat(new AuthService(client, principals, autoApproval, true).authenticate("test-code"))
        .contains(existing);
    verifyNoInteractions(autoApproval);
  }
}
