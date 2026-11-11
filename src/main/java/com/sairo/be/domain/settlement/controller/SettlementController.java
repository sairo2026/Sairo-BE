package com.sairo.be.domain.settlement.controller;

import com.sairo.be.domain.settlement.dto.response.MonthlyMemoResponse;
import com.sairo.be.domain.settlement.dto.response.MonthlySettlementListResponse;
import com.sairo.be.domain.settlement.dto.response.MonthlySettlementResponse;
import com.sairo.be.domain.settlement.dto.response.SettlementDetailsResponse;
import com.sairo.be.domain.settlement.service.MonthlyMemoService;
import com.sairo.be.domain.settlement.service.SettlementService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/settlements")
@RequiresAccess(AccessPermission.AGENT_OR_OWNER)
@Tag(name = "SETTLE", description = "월말결산")
public class SettlementController {

  private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

  private final SettlementService settlementService;
  private final MonthlyMemoService memoService;

  public SettlementController(SettlementService settlementService, MonthlyMemoService memoService) {
    this.settlementService = settlementService;
    this.memoService = memoService;
  }

  @Operation(summary = "월말결산 목록", description = "사무소 생성월부터 현재 달까지 월별 체결계약 수를 돌려준다. 현재 달은 집계 중이다.")
  @GetMapping
  public MonthlySettlementListResponse list(@AuthenticationPrincipal StaffPrincipal principal) {
    return settlementService.list(principal);
  }

  @Operation(
      summary = "특정 월 결산",
      description = "11개 지표군의 18개 세부 값과 부가 표시값을 돌려준다. yearMonth는 YYYY-MM이고 사무소 생성월부터 현재 달까지다.")
  @GetMapping("/{yearMonth}")
  public MonthlySettlementResponse get(
      @PathVariable String yearMonth, @AuthenticationPrincipal StaffPrincipal principal) {
    return settlementService.get(principal, yearMonth);
  }

  @Operation(summary = "지표별 상세 내역", description = "metric에 지표 이름을 보내면 같은 조건의 원본 레코드를 돌려준다.")
  @GetMapping("/{yearMonth}/details")
  public SettlementDetailsResponse details(
      @PathVariable String yearMonth,
      @RequestParam(required = false) String metric,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return settlementService.details(principal, yearMonth, metric, PageQuery.of(page, size));
  }

  @Operation(summary = "결산 내보내기", description = "UTF-8 CSV 한 행으로 돌려준다. 부가 표시값 네 열이 마지막이다.")
  @GetMapping("/{yearMonth}/export")
  public ResponseEntity<byte[]> export(
      @PathVariable String yearMonth, @AuthenticationPrincipal StaffPrincipal principal) {
    String csv = settlementService.exportCsv(principal, yearMonth);
    return ResponseEntity.ok()
        .contentType(CSV)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("settlement-" + yearMonth + ".csv", StandardCharsets.UTF_8)
                .build()
                .toString())
        .cacheControl(CacheControl.noStore())
        .body(csv.getBytes(StandardCharsets.UTF_8));
  }

  @Operation(summary = "월간 메모 조회", description = "작성 전 달은 content null, version 0이다.")
  @GetMapping("/{yearMonth}/memo")
  public MonthlyMemoResponse memo(
      @PathVariable String yearMonth, @AuthenticationPrincipal StaffPrincipal principal) {
    return memoService.get(principal, yearMonth);
  }

  @Operation(
      summary = "월간 메모 작성·수정",
      description =
          "If-Match에 마지막으로 읽은 version을 보낸다. 처음 작성은 If-Match 0이다. content가 빈 문자열이면 메모를 비운다.")
  @PutMapping("/{yearMonth}/memo")
  public MonthlyMemoResponse putMemo(
      @PathVariable String yearMonth,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return memoService.put(principal, yearMonth, ifMatch, body);
  }
}
