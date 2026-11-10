package com.sairo.be.domain.customer.controller;

import com.sairo.be.domain.customer.dto.response.CustomerConsultationListResponse;
import com.sairo.be.domain.customer.dto.response.CustomerConsultationResponse;
import com.sairo.be.domain.customer.dto.response.CustomerConsultationRevisionListResponse;
import com.sairo.be.domain.customer.dto.response.CustomerMergeResponse;
import com.sairo.be.domain.customer.service.CustomerConsultationService;
import com.sairo.be.domain.customer.service.CustomerMergeService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.IfMatchVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
@RequestMapping("/api")
@Tag(name = "CUSTOMER", description = "상담 이력과 고객 병합")
public class CustomerConsultationController {

  private final CustomerConsultationService consultationService;
  private final CustomerMergeService mergeService;

  public CustomerConsultationController(
      CustomerConsultationService consultationService, CustomerMergeService mergeService) {
    this.consultationService = consultationService;
    this.mergeService = mergeService;
  }

  @Operation(summary = "고객 병합", description = "대표만 실행한다. If-Match에 남는 고객 version을 보낸다.")
  @PostMapping("/customers/merge")
  @RequiresAccess(AccessPermission.OWNER)
  public CustomerMergeResponse merge(
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return mergeService.merge(principal, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "상담이력 목록", description = "병합 이력으로 연결된 고객의 상담을 함께 돌려준다.")
  @GetMapping("/customers/{customerId}/consultations")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public CustomerConsultationListResponse listConsultations(
      @PathVariable Long customerId, @AuthenticationPrincipal StaffPrincipal principal) {
    return consultationService.list(principal, customerId);
  }

  @Operation(summary = "상담이력 등록", description = "작성자는 서버가 로그인한 구성원으로 기록한다.")
  @PostMapping("/customers/{customerId}/consultations")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  @ResponseStatus(HttpStatus.CREATED)
  public CustomerConsultationResponse createConsultation(
      @PathVariable Long customerId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return consultationService.create(principal, customerId, body);
  }

  @Operation(summary = "상담 정정", description = "If-Match에 상담 version을 보내고 이전 값은 정정 이력에 보존한다.")
  @PatchMapping("/customer-consultations/{consultationId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public CustomerConsultationResponse updateConsultation(
      @PathVariable Long consultationId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return consultationService.update(
        principal, consultationId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "상담 정정 이력 조회")
  @GetMapping("/customer-consultations/{consultationId}/revisions")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public CustomerConsultationRevisionListResponse revisions(
      @PathVariable Long consultationId, @AuthenticationPrincipal StaffPrincipal principal) {
    return consultationService.revisions(principal, consultationId);
  }
}
