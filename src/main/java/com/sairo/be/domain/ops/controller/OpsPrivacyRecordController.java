package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OpsPrivacyRecordRequests.AuthorityRequestCloseRequest;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyRecordRequests.AuthorityRequestCreateRequest;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyRecordRequests.GuestPrivacyIntakeResolveRequest;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyRecordRequests.ProviderContractCopyCreateRequest;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyRecordResponses.AuthorityRequestItem;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyRecordResponses.AuthorityRequestResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyRecordResponses.GuestPrivacyIntakeItem;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyRecordResponses.GuestPrivacyIntakeResolveResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyRecordResponses.ProviderContractCopyListResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyRecordResponses.ProviderContractCopyResponse;
import com.sairo.be.domain.ops.service.OpsPrivacyRecordService;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.security.access.RequiresOperatorRole;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
@Tag(name = "OPS", description = "비회원 권리 요청·자료 요청·계약서 사본")
public class OpsPrivacyRecordController {

  private final OpsPrivacyRecordService service;

  public OpsPrivacyRecordController(OpsPrivacyRecordService service) {
    this.service = service;
  }

  @Operation(
      summary = "비회원 권리 요청 접수 큐",
      description = "status·officeId로 거른다. 접수 상태를 내부 목표 기한 순으로 먼저 보이고 연락처는 가려서 보인다.")
  @GetMapping("/guest-privacy-intakes")
  @RequiresOperatorRole(OperatorRole.PRIVACY)
  public PageResponse<GuestPrivacyIntakeItem> intakes(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Long officeId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.intakes(status, officeId, PageQuery.of(page, size));
  }

  @Operation(
      summary = "비회원 권리 요청 본인 확인과 전환·종결",
      description = "전환하면 링크 발급 사무소가 처리 책임인 권리 요청을 만든다. 이미 전환한 접수는 409다.")
  @PostMapping("/guest-privacy-intakes/{intakeId}/resolve")
  @RequiresOperatorRole(OperatorRole.PRIVACY)
  public GuestPrivacyIntakeResolveResponse resolve(
      @PathVariable Long intakeId,
      @Valid @RequestBody GuestPrivacyIntakeResolveRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.resolveIntake(principal.operatorId(), intakeId, request);
  }

  @Operation(
      summary = "수사기관 등 자료 요청 기록 목록",
      description = "decision·open(미종결만)으로 거른다. 종결 화면을 위해 보안관리자도 볼 수 있다.")
  @GetMapping("/authority-requests")
  @RequiresOperatorRole({OperatorRole.PRIVACY, OperatorRole.SECURITY_ADMIN})
  public PageResponse<AuthorityRequestItem> authorityRequests(
      @RequestParam(required = false) String decision,
      @RequestParam(required = false) Boolean open,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.authorityRequests(decision, open, PageQuery.of(page, size));
  }

  @Operation(summary = "수사기관 등 자료 요청 접수 기록", description = "개인정보담당이 기관·문서 번호·법적 근거·대상·요청 범위를 기록한다.")
  @PostMapping("/authority-requests")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresOperatorRole(OperatorRole.PRIVACY)
  public AuthorityRequestResponse recordAuthorityRequest(
      @Valid @RequestBody AuthorityRequestCreateRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.recordAuthorityRequest(principal.operatorId(), request);
  }

  @Operation(
      summary = "제공 결정과 종결",
      description =
          "제공·일부 제공은 기록자와 다른 보안관리자만 하고 기록자 본인이면 403 SELF_APPROVAL_FORBIDDEN이다. 종결하면 삭제 예정일을 정한다.")
  @PostMapping("/authority-requests/{requestId}/close")
  @RequiresOperatorRole({OperatorRole.PRIVACY, OperatorRole.SECURITY_ADMIN})
  public AuthorityRequestResponse closeAuthorityRequest(
      @PathVariable Long requestId,
      @Valid @RequestBody AuthorityRequestCloseRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.closeAuthorityRequest(principal.operatorId(), requestId, request);
  }

  @Operation(summary = "연동별 공급자 계약서 사본 목록", description = "저장 위치는 담지 않는다.")
  @GetMapping("/integrations/{integrationId}/contract-copies")
  @RequiresOperatorRole(OperatorRole.PRIVACY)
  public ProviderContractCopyListResponse contractCopies(@PathVariable Long integrationId) {
    return service.contractCopies(integrationId);
  }

  @Operation(
      summary = "공급자 계약서 사본 등록",
      description = "비공개 저장소에 올린 사본의 위치와 SHA-256 해시를 등록한다. 같은 연동·종류·버전은 409다.")
  @PostMapping("/integrations/{integrationId}/contract-copies")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresOperatorRole(OperatorRole.PRIVACY)
  public ProviderContractCopyResponse registerContractCopy(
      @PathVariable Long integrationId,
      @Valid @RequestBody ProviderContractCopyCreateRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.registerContractCopy(principal.operatorId(), integrationId, request);
  }
}
