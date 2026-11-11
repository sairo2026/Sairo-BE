package com.sairo.be.domain.contract.controller;

import com.sairo.be.domain.contract.dto.response.ContractCreateResponse;
import com.sairo.be.domain.contract.dto.response.ContractStateResponse;
import com.sairo.be.domain.contract.service.ContractCommandService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/properties/{propertyId}/contracts")
@RequiresAccess(AccessPermission.AGENT_OR_OWNER)
@Tag(name = "CONTRACT", description = "계약 등록")
public class PropertyContractController {

  private final ContractCommandService commandService;

  public PropertyContractController(ContractCommandService commandService) {
    this.commandService = commandService;
  }

  @Operation(
      summary = "계약 초안 저장",
      description = "거래유형과 담당자만 필수다. 당사자·체결일·금액·잔금 예정일·임대기간은 비워 둘 수 있다.")
  @PostMapping("/drafts")
  @ResponseStatus(HttpStatus.CREATED)
  public ContractStateResponse createDraft(
      @PathVariable Long propertyId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return commandService.createDraft(principal, propertyId, body);
  }

  @Operation(
      summary = "계약 즉시 체결 등록",
      description = "체결에 필요한 값을 모두 갖춘 계약을 바로 체결 상태로 만든다. 같은 거래유형의 활성 거래제안은 완료가 된다.")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ContractCreateResponse create(
      @PathVariable Long propertyId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return commandService.create(principal, propertyId, body);
  }
}
