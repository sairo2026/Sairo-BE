package com.sairo.be.domain.auth.controller;

import com.sairo.be.domain.auth.dto.request.MarketingUnsubscribeRequest;
import com.sairo.be.domain.auth.dto.request.TermsAgreementRequest;
import com.sairo.be.domain.auth.dto.response.MarketingUnsubscribeResponse;
import com.sairo.be.domain.auth.dto.response.TermsCatalogResponse;
import com.sairo.be.domain.auth.dto.response.TermsReconsentResponse;
import com.sairo.be.domain.auth.mapper.AuthMapper;
import com.sairo.be.domain.auth.service.MarketingUnsubscribeService;
import com.sairo.be.domain.auth.service.ReconsentService;
import com.sairo.be.domain.auth.service.TermsAgreementService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.web.ClientRequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@Tag(name = "TERMS", description = "약관 목록, 동의·철회 기록, 재동의, 광고성 수신 거부")
public class TermsController {

  private final TermsAgreementService termsAgreementService;
  private final ReconsentService reconsentService;
  private final MarketingUnsubscribeService marketingUnsubscribeService;

  public TermsController(
      TermsAgreementService termsAgreementService,
      ReconsentService reconsentService,
      MarketingUnsubscribeService marketingUnsubscribeService) {
    this.termsAgreementService = termsAgreementService;
    this.reconsentService = reconsentService;
    this.marketingUnsubscribeService = marketingUnsubscribeService;
  }

  @Operation(summary = "약관 목록 조회", description = "시행 중인 약관 목록을 돌려준다. context 기본값은 SIGNUP이다.")
  @RequiresAccess(AccessPermission.PUBLIC)
  @GetMapping("/api/terms")
  public TermsCatalogResponse terms(@RequestParam(defaultValue = "SIGNUP") String context) {
    return AuthMapper.toCatalog(termsAgreementService.findCatalog(context));
  }

  @Operation(summary = "약관 동의·철회 기록", description = "시행 버전별 약관 동의와 철회를 기록한다.")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  @PostMapping("/api/me/terms-agreements")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void recordAgreement(
      @Valid @RequestBody TermsAgreementRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    termsAgreementService.recordAgreement(principal.userId(), request);
  }

  @Operation(summary = "재동의 대기 항목 조회", description = "재동의가 필요한 약관 개정 항목을 돌려준다.")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  @GetMapping("/api/me/terms-reconsent")
  public TermsReconsentResponse reconsent(@AuthenticationPrincipal StaffPrincipal principal) {
    return AuthMapper.toReconsent(reconsentService.findPending(principal.userId()));
  }

  @Operation(summary = "광고성 이메일 수신 거부", description = "서명된 수신 거부 링크 토큰으로 마케팅 수신 동의를 철회한다.")
  @RequiresAccess(AccessPermission.PUBLIC)
  @PostMapping("/api/public/marketing-unsubscribe")
  public MarketingUnsubscribeResponse unsubscribe(
      @Valid @RequestBody MarketingUnsubscribeRequest request, HttpServletRequest httpRequest) {
    return marketingUnsubscribeService.unsubscribe(
        request.token(), ClientRequestInfo.from(httpRequest).ipAddress());
  }
}
