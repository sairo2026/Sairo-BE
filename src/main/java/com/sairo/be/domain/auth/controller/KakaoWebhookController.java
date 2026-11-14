package com.sairo.be.domain.auth.controller;

import com.sairo.be.domain.auth.dto.request.KakaoUnlinkWebhookRequest;
import com.sairo.be.domain.auth.mapper.KakaoUnlinkWebhookMapper;
import com.sairo.be.domain.auth.service.KakaoUnlinkReceiveService;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/webhooks/kakao")
@RequiresAccess(AccessPermission.EXTERNAL_WEBHOOK)
@Tag(name = "WEBHOOK", description = "카카오 쪽 연결 끊김 수신")
public class KakaoWebhookController {

  private final KakaoUnlinkWebhookMapper mapper;
  private final KakaoUnlinkReceiveService receiveService;

  public KakaoWebhookController(
      KakaoUnlinkWebhookMapper mapper, KakaoUnlinkReceiveService receiveService) {
    this.mapper = mapper;
    this.receiveService = receiveService;
  }

  @Operation(
      summary = "카카오 연결 끊김 수신",
      description = "Authorization 헤더의 KakaoAK 어드민 키로 인증한다. 사용자 존재 여부와 관계없이 같은 200 빈 응답이다.")
  @SecurityRequirements
  @PostMapping("/unlink")
  public void unlink(HttpServletRequest request) {
    KakaoUnlinkWebhookRequest webhook = mapper.from(request);
    receiveService.receive(webhook.appId(), webhook.userId(), webhook.referrerType());
  }
}
