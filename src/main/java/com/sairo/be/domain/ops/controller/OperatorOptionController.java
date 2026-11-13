package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OpsOperatorOptionListResponse;
import com.sairo.be.domain.ops.service.OperatorManagementService;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops")
@RequiresAccess(AccessPermission.OPERATOR)
@Tag(name = "OPS", description = "운영자 선택 목록")
public class OperatorOptionController {

  private final OperatorManagementService service;

  public OperatorOptionController(OperatorManagementService service) {
    this.service = service;
  }

  @Operation(summary = "운영자 선택 목록", description = "활성 운영자의 ID·이름·역할만 이름순으로 준다. 모든 운영 역할이 조회한다.")
  @GetMapping("/operators/options")
  public OpsOperatorOptionListResponse options() {
    return service.options();
  }
}
