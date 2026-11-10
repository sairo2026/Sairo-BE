package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.CandidatePropertyAddResponse;
import com.sairo.be.domain.visit.dto.response.CustomerLinkIssueResponse;
import com.sairo.be.domain.visit.dto.response.CustomerLinkReissueResponse;
import com.sairo.be.domain.visit.dto.response.VisitCoordinationCreateResponse;
import com.sairo.be.domain.visit.dto.response.VisitCoordinationDetailResponse;
import com.sairo.be.domain.visit.dto.response.VisitCoordinationListResponse;
import com.sairo.be.domain.visit.service.CandidatePropertyService;
import com.sairo.be.domain.visit.service.VisitCoordinationCommandService;
import com.sairo.be.domain.visit.service.VisitCoordinationQueryService;
import com.sairo.be.domain.visit.service.VisitCustomerLinkService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.IfMatchVersion;
import com.sairo.be.global.support.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/visit-coordinations")
@Tag(name = "VISIT", description = "방문 조율 생성·조회, 후보 매물, 고객 시간 수집 링크")
public class VisitCoordinationController {

  private final VisitCoordinationCommandService commandService;
  private final VisitCoordinationQueryService queryService;
  private final CandidatePropertyService candidateService;
  private final VisitCustomerLinkService linkService;

  public VisitCoordinationController(
      VisitCoordinationCommandService commandService,
      VisitCoordinationQueryService queryService,
      CandidatePropertyService candidateService,
      VisitCustomerLinkService linkService) {
    this.commandService = commandService;
    this.queryService = queryService;
    this.candidateService = candidateService;
    this.linkService = linkService;
  }

  @Operation(summary = "조율 건 생성", description = "고객 1명과 후보 매물 1~20개로 조율 건을 만든다. 출입승인 없이도 만들 수 있다.")
  @PostMapping
  @RequiresAccess(AccessPermission.STAFF)
  @ResponseStatus(HttpStatus.CREATED)
  public VisitCoordinationCreateResponse create(
      @RequestBody JsonNode body, @AuthenticationPrincipal StaffPrincipal principal) {
    return commandService.create(principal, body);
  }

  @Operation(
      summary = "조율 건 목록",
      description = "status는 반복해서 보낼 수 있고 statusCounts는 상태 필터와 무관하게 조회자가 볼 수 있는 범위 기준이다.")
  @GetMapping
  @RequiresAccess(AccessPermission.STAFF)
  public VisitCoordinationListResponse list(
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.list(principal, status, PageQuery.of(page, size));
  }

  @Operation(summary = "조율 건 상세", description = "중개보조원은 본인에게 배정된 조율 건만 조회한다.")
  @GetMapping("/{coordinationId}")
  @RequiresAccess(AccessPermission.STAFF)
  public VisitCoordinationDetailResponse detail(
      @PathVariable Long coordinationId,
      @RequestParam(required = false) Long changeScheduleItemId,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.detail(principal, coordinationId, changeScheduleItemId);
  }

  @Operation(
      summary = "후보 매물 추가",
      description = "후보는 자기 사무소 매물과 허용받은 매물을 합쳐 20개까지다. If-Match는 보내면 검사한다.")
  @PostMapping("/{coordinationId}/candidate-properties")
  @RequiresAccess(AccessPermission.STAFF)
  @ResponseStatus(HttpStatus.CREATED)
  public CandidatePropertyAddResponse addCandidate(
      @PathVariable Long coordinationId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return candidateService.add(principal, coordinationId, expectedVersion(ifMatch), body);
  }

  @Operation(summary = "후보 매물 제거", description = "시간 수집 중인 조율 건에서만 제거한다. If-Match는 보내면 검사한다.")
  @DeleteMapping("/{coordinationId}/candidate-properties/{candidateId}")
  @RequiresAccess(AccessPermission.STAFF)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeCandidate(
      @PathVariable Long coordinationId,
      @PathVariable Long candidateId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal StaffPrincipal principal) {
    candidateService.remove(principal, coordinationId, candidateId, expectedVersion(ifMatch));
  }

  @Operation(summary = "고객 시간 수집 링크 발급", description = "토큰 원문은 이 응답에서만 돌려주고 응답기한은 서버가 정한다.")
  @PostMapping("/{coordinationId}/customer-link")
  @RequiresAccess(AccessPermission.STAFF)
  @ResponseStatus(HttpStatus.CREATED)
  public CustomerLinkIssueResponse issueLink(
      @PathVariable Long coordinationId, @AuthenticationPrincipal StaffPrincipal principal) {
    return linkService.issue(principal, coordinationId);
  }

  @Operation(summary = "고객 시간 수집 링크 재발급", description = "이전 링크는 즉시 폐기한다.")
  @PostMapping("/{coordinationId}/customer-link/reissue")
  @RequiresAccess(AccessPermission.STAFF)
  public CustomerLinkReissueResponse reissueLink(
      @PathVariable Long coordinationId, @AuthenticationPrincipal StaffPrincipal principal) {
    return linkService.reissue(principal, coordinationId);
  }

  private static Long expectedVersion(String ifMatch) {
    return ifMatch == null ? null : IfMatchVersion.parse(ifMatch);
  }
}
