package com.sairo.be.domain.billing.controller;

import com.sairo.be.domain.billing.dto.request.GrantAuthRequest;
import com.sairo.be.domain.billing.dto.response.GrantAuthResponse;
import com.sairo.be.domain.billing.service.GrantAuthService;
import com.sairo.be.global.security.GrantType;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.web.ClientRequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/grant-auth")
@Tag(name = "BILLING", description = "요금·구독·결제")
public class GrantAuthController {

  private final GrantAuthService grantAuthService;

  public GrantAuthController(GrantAuthService grantAuthService) {
    this.grantAuthService = grantAuthService;
  }

  @Operation(summary = "반환권한 재인증", description = "일반 로그인 세션을 만들지 않고 반환 전용 세션을 발급한다.")
  @PostMapping("/return")
  @RequiresAccess(AccessPermission.PUBLIC)
  public GrantAuthResponse authenticateReturn(
      @Valid @RequestBody GrantAuthRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {
    return grantAuthService.authenticate(
        GrantType.RETURN,
        request,
        ClientRequestInfo.from(httpRequest).ipAddress(),
        httpRequest,
        httpResponse);
  }

  @Operation(summary = "보관권한 재인증", description = "일반 로그인 세션을 만들지 않고 보관 전용 세션을 발급한다.")
  @PostMapping("/archive")
  @RequiresAccess(AccessPermission.PUBLIC)
  public GrantAuthResponse authenticateArchive(
      @Valid @RequestBody GrantAuthRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {
    return grantAuthService.authenticate(
        GrantType.ARCHIVE,
        request,
        ClientRequestInfo.from(httpRequest).ipAddress(),
        httpRequest,
        httpResponse);
  }
}
