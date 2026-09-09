package com.sairo.be.domain.coordination.controller;

import com.sairo.be.domain.coordination.dto.request.AvailableTimesSubmitRequest;
import com.sairo.be.domain.coordination.dto.response.PublicVisitResponse;
import com.sairo.be.domain.coordination.service.PublicVisitResponseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/public/visit-responses/{token}")
@RequiredArgsConstructor
@Tag(name = "RESPOND", description = "세입자·구매희망자가 URL 토큰만으로 임장 가능 시간에 응답한다.")
public class PublicVisitResponseController {

  private final PublicVisitResponseService publicVisitResponseService;

  @Operation(
      summary = "공개 응답 조회",
      description = "세입자 또는 구매희망자가 URL 토큰으로 자신의 임장 조율 응답 상태와 후보 시간을 조회한다.")
  @SecurityRequirements
  @GetMapping
  public PublicVisitResponse get(@PathVariable String token) {
    return publicVisitResponseService.getByToken(token);
  }

  @Operation(summary = "가능한 시간 제출", description = "제공된 후보 시간 중 응답자가 가능한 시간을 선택해 제출한다.")
  @SecurityRequirements
  @PostMapping("/available-times")
  public PublicVisitResponse submitAvailableTimes(
      @PathVariable String token, @Valid @RequestBody AvailableTimesSubmitRequest request) {
    return publicVisitResponseService.submitAvailableTimes(token, request);
  }

  @Operation(summary = "가능한 시간 없음 제출", description = "제공된 후보 시간 중 가능한 시간이 없음을 제출한다.")
  @SecurityRequirements
  @PostMapping("/no-availability")
  public PublicVisitResponse submitNoAvailability(@PathVariable String token) {
    return publicVisitResponseService.submitNoAvailability(token);
  }
}
