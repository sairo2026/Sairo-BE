package com.sairo.be.domain.support.controller;

import com.sairo.be.domain.support.dto.response.EnterpriseLeadCreateResponse;
import com.sairo.be.domain.support.dto.response.EnterpriseLeadStatusResponse;
import com.sairo.be.domain.support.dto.response.EnterpriseQuoteDecisionResponse;
import com.sairo.be.domain.support.service.EnterpriseLeadService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/enterprise-leads")
@Tag(name = "SUPPORT", description = "기업 도입 문의")
public class EnterpriseLeadController {

  static final String TRACKING_KEY_HEADER = "X-Tracking-Key";

  private final EnterpriseLeadService leadService;

  public EnterpriseLeadController(EnterpriseLeadService leadService) {
    this.leadService = leadService;
  }

  @Operation(
      summary = "기업 도입 문의 접수",
      description = "로그인 없이 접수한다. 조회키는 이 응답에서 한 번만 돌려주고 서버에는 해시만 저장한다.")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresAccess(AccessPermission.PUBLIC)
  public EnterpriseLeadCreateResponse create(
      @RequestBody JsonNode body, HttpServletRequest request, HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
    return leadService.create(body, ClientRequestInfo.from(request).ipAddress());
  }

  @Operation(
      summary = "조회키로 진행 상태 조회",
      description = "X-Tracking-Key 헤더로 확인한다. 문의 원문과 연락처는 돌려주지 않는다.")
  @GetMapping("/{leadId}")
  @RequiresAccess(AccessPermission.PUBLIC)
  public ResponseEntity<EnterpriseLeadStatusResponse> status(
      @PathVariable Long leadId,
      @RequestHeader(value = TRACKING_KEY_HEADER, required = false) String trackingKey,
      HttpServletRequest request) {
    EnterpriseLeadStatusResponse response =
        leadService.status(leadId, trackingKey, ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
  }

  @Operation(
      summary = "기업 견적 수락",
      description = "최신 버전이고 대체되지 않았고 유효기한 전인 견적만 수락할 수 있다. 요청 본문은 없다.")
  @PostMapping("/{leadId}/quotes/{quoteId}/accept")
  @RequiresAccess(AccessPermission.PUBLIC)
  public ResponseEntity<EnterpriseQuoteDecisionResponse> accept(
      @PathVariable Long leadId,
      @PathVariable Long quoteId,
      @RequestHeader(value = TRACKING_KEY_HEADER, required = false) String trackingKey,
      @RequestBody(required = false) JsonNode body,
      HttpServletRequest request) {
    EnterpriseQuoteDecisionResponse response =
        leadService.accept(
            leadId, quoteId, trackingKey, body, ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
  }

  @Operation(summary = "기업 견적 거절", description = "수락과 같은 조건의 견적만 거절할 수 있다. 요청 본문은 없다.")
  @PostMapping("/{leadId}/quotes/{quoteId}/reject")
  @RequiresAccess(AccessPermission.PUBLIC)
  public ResponseEntity<EnterpriseQuoteDecisionResponse> reject(
      @PathVariable Long leadId,
      @PathVariable Long quoteId,
      @RequestHeader(value = TRACKING_KEY_HEADER, required = false) String trackingKey,
      @RequestBody(required = false) JsonNode body,
      HttpServletRequest request) {
    EnterpriseQuoteDecisionResponse response =
        leadService.reject(
            leadId, quoteId, trackingKey, body, ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
  }
}
