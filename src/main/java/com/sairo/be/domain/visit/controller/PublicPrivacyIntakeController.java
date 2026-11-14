package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.GuestPrivacyIntakeResponse;
import com.sairo.be.domain.visit.service.GuestPrivacyIntakeService;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.web.ClientRequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/public/privacy-intakes")
@Tag(name = "RESPOND", description = "비회원 링크 응답")
public class PublicPrivacyIntakeController {

  private final GuestPrivacyIntakeService intakeService;

  public PublicPrivacyIntakeController(GuestPrivacyIntakeService intakeService) {
    this.intakeService = intakeService;
  }

  @Operation(
      summary = "비회원 개인정보 권리 요청 접수",
      description = "링크 토큰을 본문으로 받는다. 개인정보 안내 버전이 시행 버전과 다르면 409다. 접수 번호는 돌려주지 않는다.")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public GuestPrivacyIntakeResponse receive(
      @RequestBody JsonNode body, HttpServletRequest request, HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
    ClientRequestInfo client = ClientRequestInfo.from(request);
    return intakeService.receive(body, client.ipAddress(), client.userAgent());
  }
}
