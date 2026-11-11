package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.ConfirmedScheduleResponses.PublicConfirmedVisitResponse;
import com.sairo.be.domain.visit.dto.response.CustomerAvailableTimesResponse;
import com.sairo.be.domain.visit.dto.response.PublicVisitLinkResponse;
import com.sairo.be.domain.visit.service.PublicConfirmedScheduleService;
import com.sairo.be.domain.visit.service.PublicCustomerLinkQueryService;
import com.sairo.be.domain.visit.service.PublicCustomerLinkSubmitService;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.web.ClientRequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/public/customer-visit-links/{token}")
@Tag(name = "RESPOND", description = "비회원 링크 응답")
public class PublicCustomerVisitLinkController {

  private final PublicCustomerLinkQueryService queryService;
  private final PublicCustomerLinkSubmitService submitService;
  private final PublicConfirmedScheduleService confirmedService;

  public PublicCustomerVisitLinkController(
      PublicCustomerLinkQueryService queryService,
      PublicCustomerLinkSubmitService submitService,
      PublicConfirmedScheduleService confirmedService) {
    this.queryService = queryService;
    this.submitService = submitService;
    this.confirmedService = confirmedService;
  }

  @Operation(
      summary = "고객 확정 일정 조회",
      description = "읽기 전용이며 마지막 방문 종료 후 24시간까지 연다. 취소·변경된 이전 버전은 재확인 안내로 대체한다.")
  @GetMapping("/confirmed")
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<PublicConfirmedVisitResponse> confirmed(
      @PathVariable String token, HttpServletRequest request) {
    PublicConfirmedVisitResponse body =
        confirmedService.customer(token, ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  @Operation(summary = "고객용 후보 매물·수집 상태 조회", description = "상세주소와 승인자 연락처는 돌려주지 않는다. 응답은 캐시하지 않는다.")
  @GetMapping
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<PublicVisitLinkResponse> get(
      @PathVariable String token, HttpServletRequest request) {
    PublicVisitLinkResponse body =
        queryService.get(token, ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  @Operation(
      summary = "고객 가능 시간 제출·수정",
      description = "제출할 때마다 응답 버전이 1 늘고 이전 버전은 보존한다. 빈 배열은 제출 취소다.")
  @PostMapping("/available-times")
  @RequiresAccess(AccessPermission.GUEST_LINK)
  public ResponseEntity<CustomerAvailableTimesResponse> submit(
      @PathVariable String token,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body,
      HttpServletRequest request) {
    CustomerAvailableTimesResponse response =
        submitService.submit(
            token, body, idempotencyKey, ClientRequestInfo.from(request).ipAddress());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
  }
}
