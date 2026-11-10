package com.sairo.be.domain.customer.controller;

import com.sairo.be.domain.customer.dto.response.CandidatePropertyResponse;
import com.sairo.be.domain.customer.dto.response.DemandConditionResponse;
import com.sairo.be.domain.customer.dto.response.OwnerOccupantResponse;
import com.sairo.be.domain.customer.dto.response.PropertyInterestResponse;
import com.sairo.be.domain.customer.service.CustomerLinkService;
import com.sairo.be.domain.customer.service.CustomerQueryService;
import com.sairo.be.domain.customer.service.DemandConditionService;
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
@Tag(name = "CUSTOMER", description = "소유·점유 관계, 관심 매물, 희망조건")
public class CustomerRelationController {

  private final CustomerLinkService linkService;
  private final DemandConditionService demandService;
  private final CustomerQueryService queryService;

  public CustomerRelationController(
      CustomerLinkService linkService,
      DemandConditionService demandService,
      CustomerQueryService queryService) {
    this.linkService = linkService;
    this.demandService = demandService;
    this.queryService = queryService;
  }

  @Operation(summary = "매물에 소유자·점유자 연결")
  @PostMapping("/properties/{propertyId}/owner-occupants")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  @ResponseStatus(HttpStatus.CREATED)
  public OwnerOccupantResponse createOwnerOccupant(
      @PathVariable Long propertyId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return linkService.createOwnerOccupant(principal, propertyId, body);
  }

  @Operation(summary = "소유·점유 관계 종료일 수정", description = "If-Match에 고객 version을 보낸다.")
  @PatchMapping("/owner-occupants/{linkId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public OwnerOccupantResponse updateOwnerOccupant(
      @PathVariable Long linkId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return linkService.updateOwnerOccupant(principal, linkId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "관심 매물 관계 등록")
  @PostMapping("/customers/{customerId}/property-interests")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  @ResponseStatus(HttpStatus.CREATED)
  public PropertyInterestResponse createInterest(
      @PathVariable Long customerId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return linkService.createInterest(principal, customerId, body);
  }

  @Operation(summary = "관심 매물 관계 종료일·메모 수정", description = "If-Match에 고객 version을 보낸다.")
  @PatchMapping("/customer-property-interests/{interestId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PropertyInterestResponse updateInterest(
      @PathVariable Long interestId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return linkService.updateInterest(principal, interestId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "수요 고객 희망조건 등록")
  @PostMapping("/customers/{customerId}/demand-conditions")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  @ResponseStatus(HttpStatus.CREATED)
  public DemandConditionResponse createDemandCondition(
      @PathVariable Long customerId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return demandService.create(principal, customerId, body);
  }

  @Operation(summary = "희망조건 수정", description = "If-Match에 고객 version을 보낸다.")
  @PatchMapping("/demand-conditions/{conditionId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public DemandConditionResponse updateDemandCondition(
      @PathVariable Long conditionId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return demandService.update(principal, conditionId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "희망조건 기준 후보 매물 필터 조회", description = "단순 필터이며 적합성 점수는 없다.")
  @GetMapping("/demand-conditions/{conditionId}/candidate-properties")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public CandidatePropertyResponse candidateProperties(
      @PathVariable Long conditionId, @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.candidateProperties(principal, conditionId);
  }
}
