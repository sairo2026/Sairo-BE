package com.sairo.be.domain.office.controller;

import com.sairo.be.domain.office.dto.request.OfficeLookupRequest;
import com.sairo.be.domain.office.dto.request.OfficeRegistrationCancelRequest;
import com.sairo.be.domain.office.dto.request.OfficeRegistrationCreateRequest;
import com.sairo.be.domain.office.dto.response.OfficeLookupResponse;
import com.sairo.be.domain.office.dto.response.OfficeRegistrationCancelResponse;
import com.sairo.be.domain.office.dto.response.OfficeRegistrationCreateResponse;
import com.sairo.be.domain.office.dto.response.OfficeRegistrationEvidenceResponse;
import com.sairo.be.domain.office.dto.response.OfficeRegistrationStatusResponse;
import com.sairo.be.domain.office.dto.response.ReapplicationStartResponse;
import com.sairo.be.domain.office.service.EvidenceSubmissionService;
import com.sairo.be.domain.office.service.OfficeRegistrationService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Profile("!migrate")
@RestController
@RequestMapping("/api")
@RequiresAccess(AccessPermission.AUTHENTICATED)
@Tag(name = "OFFICE", description = "사무소 등록 신청과 증빙 제출")
public class OfficeRegistrationController {

  private final OfficeRegistrationService registrationService;
  private final EvidenceSubmissionService evidenceSubmissionService;

  public OfficeRegistrationController(
      OfficeRegistrationService registrationService,
      EvidenceSubmissionService evidenceSubmissionService) {
    this.registrationService = registrationService;
    this.evidenceSubmissionService = evidenceSubmissionService;
  }

  @Operation(summary = "사무소 존재 여부 조회", description = "개설등록관청과 개설등록번호로 기존 사무소가 있는지 확인한다.")
  @GetMapping("/offices/lookup")
  public OfficeLookupResponse lookup(@Valid @ModelAttribute OfficeLookupRequest request) {
    return registrationService.lookup(request);
  }

  @Operation(summary = "사무소 등록 신청 작성", description = "작성 중 상태의 신청을 만든다. 증빙 제출이 성공해야 심사가 시작된다.")
  @PostMapping("/office-registrations")
  @ResponseStatus(HttpStatus.CREATED)
  public OfficeRegistrationCreateResponse create(
      @Valid @RequestBody OfficeRegistrationCreateRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return registrationService.create(principal.userId(), request);
  }

  @Operation(
      summary = "증빙서류 제출",
      description = "evidenceType과 file을 같은 순서로 반복해 보낸다. 성공하면 심사 중으로 전이한다.")
  @PostMapping("/office-registrations/{registrationId}/evidence")
  public OfficeRegistrationEvidenceResponse submitEvidence(
      @PathVariable Long registrationId,
      @RequestParam("evidenceType") List<String> evidenceTypes,
      @RequestParam("file") List<MultipartFile> files,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return evidenceSubmissionService.submit(
        principal.userId(), registrationId, evidenceTypes, files);
  }

  @Operation(summary = "본인 최신 신청 상태 조회")
  @GetMapping("/office-registrations/me")
  public OfficeRegistrationStatusResponse latest(
      @AuthenticationPrincipal StaffPrincipal principal) {
    return registrationService.latest(principal.userId());
  }

  @Operation(summary = "신청 취소", description = "작성 중, 심사 중, 보완 요청 상태의 신청만 취소한다.")
  @PostMapping("/office-registrations/{registrationId}/cancel")
  public OfficeRegistrationCancelResponse cancel(
      @PathVariable Long registrationId,
      @Valid @RequestBody(required = false) OfficeRegistrationCancelRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return registrationService.cancel(principal.userId(), registrationId, request);
  }

  @Operation(summary = "반려 재신청 시작", description = "반려된 신청을 해결됨으로 표시한다. 새 신청은 이어서 직접 작성한다.")
  @PostMapping("/office-registrations/{registrationId}/reapplication-start")
  public ReapplicationStartResponse startReapplication(
      @PathVariable Long registrationId, @AuthenticationPrincipal StaffPrincipal principal) {
    return registrationService.startReapplication(principal.userId(), registrationId);
  }
}
