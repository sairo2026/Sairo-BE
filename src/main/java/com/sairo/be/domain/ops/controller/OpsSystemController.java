package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OpsSystemRequests.IncidentCreateRequest;
import com.sairo.be.domain.ops.dto.request.OpsSystemRequests.IncidentUpdateRequest;
import com.sairo.be.domain.ops.dto.request.OpsSystemRequests.IntegrationUpdateRequest;
import com.sairo.be.domain.ops.dto.request.OpsSystemRequests.PrivacyIncidentCreateRequest;
import com.sairo.be.domain.ops.dto.request.OpsSystemRequests.PrivacyIncidentUpdateRequest;
import com.sairo.be.domain.ops.dto.request.OpsSystemRequests.SystemJobRerunRequest;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.IncidentCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.IncidentItem;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.IncidentListResponse;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.IntegrationCheckResponse;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.IntegrationListResponse;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.IntegrationUpdateResponse;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.PrivacyIncidentCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.PrivacyIncidentListResponse;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.PrivacyIncidentUpdateResponse;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.SystemJobListResponse;
import com.sairo.be.domain.ops.dto.response.OpsSystemResponses.SystemJobRerunResponse;
import com.sairo.be.domain.ops.service.OpsIncidentService;
import com.sairo.be.domain.ops.service.OpsIntegrationService;
import com.sairo.be.domain.ops.service.OpsSystemJobService;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops")
@RequiresAccess(AccessPermission.OPERATOR)
@Tag(name = "OPS", description = "시스템 작업·장애·연동 설정·개인정보 사고")
public class OpsSystemController {

  private final OpsSystemJobService jobService;
  private final OpsIncidentService incidentService;
  private final OpsIntegrationService integrationService;

  public OpsSystemController(
      OpsSystemJobService jobService,
      OpsIncidentService incidentService,
      OpsIntegrationService integrationService) {
    this.jobService = jobService;
    this.incidentService = incidentService;
    this.integrationService = integrationService;
  }

  @Operation(
      summary = "시스템 작업 실행 이력",
      description = "시작 최근 순이다. jobName·status·officeId로 거른다. 항목마다 작업 종류와 재실행할 수 있는 역할을 준다.")
  @GetMapping("/system-jobs")
  @RequiresOperatorRole({
    OperatorRole.OPS,
    OperatorRole.BILLING,
    OperatorRole.PRIVACY,
    OperatorRole.MIGRATION
  })
  public SystemJobListResponse systemJobs(
      @RequestParam(required = false) String jobName,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Long officeId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return jobService.jobs(jobName, status, officeId, PageQuery.of(page, size));
  }

  @Operation(
      summary = "시스템 작업 재실행",
      description = "작업 종류별 역할만 한다. 실패한 실행만 원래 멱등키로 다시 실행하고 작업이 현재 상태를 다시 검사한다.")
  @PostMapping("/system-jobs/{jobName}/rerun")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @RequiresOperatorRole({
    OperatorRole.BILLING,
    OperatorRole.PRIVACY,
    OperatorRole.MIGRATION,
    OperatorRole.OPS
  })
  public SystemJobRerunResponse rerun(
      @PathVariable String jobName,
      @Valid @RequestBody SystemJobRerunRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return jobService.rerun(principal, jobName, request.runId());
  }

  @Operation(summary = "장애 목록", description = "감지 최근 순이다. status로 거른다.")
  @GetMapping("/incidents")
  @RequiresOperatorRole(OperatorRole.OPS)
  public IncidentListResponse incidents(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return incidentService.incidents(status, PageQuery.of(page, size));
  }

  @Operation(summary = "장애 등록", description = "감지 상태로 만들고 감지 일시는 서버 시각이다.")
  @PostMapping("/incidents")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresOperatorRole(OperatorRole.OPS)
  public IncidentCreateResponse createIncident(
      @Valid @RequestBody IncidentCreateRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return incidentService.createIncident(principal.operatorId(), request);
  }

  @Operation(
      summary = "장애 상태 갱신",
      description = "감지 → 확인 → 대응 → 복구 → 종료 순서로 한 단계씩만 바꾼다. 담당자 지정을 함께 한다.")
  @PatchMapping("/incidents/{incidentId}")
  @RequiresOperatorRole(OperatorRole.OPS)
  public IncidentItem updateIncident(
      @PathVariable Long incidentId,
      @Valid @RequestBody IncidentUpdateRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return incidentService.updateIncident(principal.operatorId(), incidentId, request);
  }

  @Operation(
      summary = "연동 설정 목록",
      description = "시험·운영 연동 상태와 마스킹된 연결 정보다. 비밀값은 돌려주지 않는다. environment·functionCode로 거른다.")
  @GetMapping("/integrations")
  @RequiresOperatorRole({OperatorRole.OPS, OperatorRole.PRIVACY})
  public IntegrationListResponse integrations(
      @RequestParam(required = false) String environment,
      @RequestParam(required = false) String functionCode,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return integrationService.integrations(environment, functionCode, PageQuery.of(page, size));
  }

  @Operation(
      summary = "연동 설정 변경",
      description = "운영 환경 활성화와 공급자·처리지역 변경은 계약·동의·고지 점검과 회사 정보 출시 준비가 끝나야 하고 변경한 운영자를 승인자로 기록한다.")
  @PatchMapping("/integrations/{integrationId}")
  @RequiresOperatorRole(OperatorRole.OPS)
  public IntegrationUpdateResponse updateIntegration(
      @PathVariable Long integrationId,
      @Valid @RequestBody IntegrationUpdateRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return integrationService.update(principal.operatorId(), integrationId, request);
  }

  @Operation(
      summary = "연동 연결 검사",
      description = "점검 호출로 연결을 시험하고 결과를 기록한다. 공급자 호출이 실패해도 200이고 결과가 FAILED다.")
  @PostMapping("/integrations/{integrationId}/check")
  @RequiresOperatorRole(OperatorRole.OPS)
  public IntegrationCheckResponse checkIntegration(
      @PathVariable Long integrationId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return integrationService.check(principal.operatorId(), integrationId);
  }

  @Operation(summary = "개인정보 사고 목록", description = "등록 최근 순이다.")
  @GetMapping("/privacy-incidents")
  @RequiresOperatorRole(OperatorRole.PRIVACY)
  public PrivacyIncidentListResponse privacyIncidents(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    return incidentService.privacyIncidents(PageQuery.of(page, size));
  }

  @Operation(summary = "개인정보 사고 등록", description = "장애 사건 한 건에 하나만 연결한다. 담당자는 요청한 운영자다.")
  @PostMapping("/privacy-incidents")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresOperatorRole(OperatorRole.PRIVACY)
  public PrivacyIncidentCreateResponse createPrivacyIncident(
      @Valid @RequestBody PrivacyIncidentCreateRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return incidentService.createPrivacyIncident(principal.operatorId(), request);
  }

  @Operation(summary = "개인정보 사고 증빙 등록", description = "신고·통지·조치 증빙의 보관 위치를 등록하거나 고친다.")
  @PatchMapping("/privacy-incidents/{privacyIncidentId}")
  @RequiresOperatorRole(OperatorRole.PRIVACY)
  public PrivacyIncidentUpdateResponse updatePrivacyIncident(
      @PathVariable Long privacyIncidentId,
      @Valid @RequestBody PrivacyIncidentUpdateRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return incidentService.updatePrivacyIncident(
        principal.operatorId(), privacyIncidentId, request.responseEvidence());
  }
}
