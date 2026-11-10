package com.sairo.be.domain.legal.controller;

import com.sairo.be.domain.legal.dto.response.PublicCompanyInfoResponse;
import com.sairo.be.domain.legal.service.PublicCompanyInfoService;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.web.ClientRequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/public")
@Tag(name = "LEGAL", description = "약관·법률 고지")
public class PublicCompanyInfoController {

  private static final CacheControl CACHE =
      CacheControl.maxAge(Duration.ofSeconds(300)).cachePublic();

  private final PublicCompanyInfoService publicCompanyInfoService;

  public PublicCompanyInfoController(PublicCompanyInfoService publicCompanyInfoService) {
    this.publicCompanyInfoService = publicCompanyInfoService;
  }

  @Operation(
      summary = "공개 사업자 정보 조회",
      description = "로그인 없이 게시된 최신 회사 제공 정보를 돌려준다. 값이 없는 항목은 생략하고 게시된 정보가 없으면 404를 돌려준다.")
  @GetMapping("/company-info")
  @RequiresAccess(AccessPermission.PUBLIC)
  public ResponseEntity<PublicCompanyInfoResponse> companyInfo(HttpServletRequest request) {
    PublicCompanyInfoResponse body =
        publicCompanyInfoService.current(ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok().cacheControl(CACHE).body(body);
  }
}
