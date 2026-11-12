package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OpsLegalOpsRequests.RetentionRuleUpdateRequest;
import com.sairo.be.domain.ops.dto.response.OpsLegalOpsResponses.KakaoUnlinkEventListResponse;
import com.sairo.be.domain.ops.dto.response.OpsLegalOpsResponses.KakaoUnlinkEventRetryResponse;
import com.sairo.be.domain.ops.dto.response.OpsLegalOpsResponses.RetentionRuleListResponse;
import com.sairo.be.domain.ops.dto.response.OpsLegalOpsResponses.RetentionRuleResponse;
import com.sairo.be.domain.ops.dto.response.OpsLegalOpsResponses.TermsHashCheckListResponse;
import com.sairo.be.domain.ops.dto.response.OpsLegalOpsResponses.TermsHashCheckRunResponse;
import com.sairo.be.domain.ops.service.OpsLegalOpsService;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.security.access.RequiresOperatorRole;
import com.sairo.be.global.support.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops")
@RequiresAccess(AccessPermission.OPERATOR)
@Tag(name = "OPS", description = "약관 원문 해시 점검·보관 기간 규칙·카카오 연결 끊기")
public class OpsLegalOpsController {

  private final OpsLegalOpsService service;

  public OpsLegalOpsController(OpsLegalOpsService service) {
    this.service = service;
  }

  @Operation(summary = "약관 해시 점검 결과", description = "시행 중이거나 예정인 약관 버전별 마지막 점검 결과다.")
  @GetMapping("/terms-hash-checks")
  @RequiresOperatorRole(OperatorRole.OPS)
  public TermsHashCheckListResponse termsHashChecks() {
    return service.termsHashChecks();
  }

  @Operation(summary = "해시 점검 즉시 실행", description = "시행 중이거나 예정인 모든 버전의 원문을 다시 읽어 등록 해시와 대조한다.")
  @PostMapping("/terms-hash-checks")
  @RequiresOperatorRole(OperatorRole.OPS)
  public TermsHashCheckRunResponse runTermsHashCheck(
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.runTermsHashCheck(principal.operatorId());
  }

  @Operation(summary = "보관 기간 규칙 목록", description = "데이터군별 보관 일수·기산점·근거·확인 상태다.")
  @GetMapping("/retention-rules")
  @RequiresOperatorRole(OperatorRole.PRIVACY)
  public RetentionRuleListResponse retentionRules() {
    return service.retentionRules();
  }

  @Operation(
      summary = "보관 기간 규칙 수정",
      description = "If-Match(현재 version)와 변경 사유가 필요하다. 이후 새로 생기거나 종결되는 행부터 적용한다.")
  @PutMapping("/retention-rules/{ruleCode}")
  @RequiresOperatorRole(OperatorRole.PRIVACY)
  public RetentionRuleResponse updateRetentionRule(
      @PathVariable String ruleCode,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @Valid @RequestBody RetentionRuleUpdateRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.updateRetentionRule(principal.operatorId(), ruleCode, ifMatch, request);
  }

  @Operation(
      summary = "카카오 연결 끊기 사건 목록",
      description = "발생 최근 순이다. direction·status로 거른다. 식별자 원문과 암호문은 돌려주지 않는다.")
  @GetMapping("/kakao-unlink-events")
  @RequiresOperatorRole(OperatorRole.PRIVACY)
  public KakaoUnlinkEventListResponse kakaoUnlinkEvents(
      @RequestParam(required = false) String direction,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.kakaoUnlinkEvents(direction, status, PageQuery.of(page, size));
  }

  @Operation(summary = "연결 끊기 수동 재시도", description = "대기·재시도 상태의 호출 작업만 지금 다시 시도하도록 대기로 돌린다.")
  @PostMapping("/kakao-unlink-events/{eventId}/retry")
  @RequiresOperatorRole(OperatorRole.PRIVACY)
  public KakaoUnlinkEventRetryResponse retryKakaoUnlink(
      @PathVariable Long eventId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.retryKakaoUnlink(principal.operatorId(), eventId);
  }
}
