package com.sairo.be.domain.legal.controller;

import com.sairo.be.domain.legal.dto.response.PolicyDocumentListResponse;
import com.sairo.be.domain.legal.service.PolicyDocumentService;
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
@RequestMapping("/api/policy-documents")
@Tag(name = "LEGAL", description = "약관·법률 고지")
public class PolicyDocumentController {

  private static final CacheControl CACHE =
      CacheControl.maxAge(Duration.ofSeconds(300)).cachePublic();

  private final PolicyDocumentService policyDocumentService;

  public PolicyDocumentController(PolicyDocumentService policyDocumentService) {
    this.policyDocumentService = policyDocumentService;
  }

  @Operation(
      summary = "시행 중인 정책 문서 본문 조회",
      description = "로그인 없이 호출한다. 문서 종류마다 시행 일시가 지난 게시 문서 중 최신 버전 하나를 돌려준다.")
  @GetMapping
  @RequiresAccess(AccessPermission.PUBLIC)
  public ResponseEntity<PolicyDocumentListResponse> inForce(HttpServletRequest request) {
    PolicyDocumentListResponse body =
        policyDocumentService.inForce(ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok().cacheControl(CACHE).body(body);
  }
}
