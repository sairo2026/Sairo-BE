package com.sairo.be.domain.property.controller;

import com.sairo.be.domain.property.dto.response.PropertyOfferListResponse;
import com.sairo.be.domain.property.dto.response.PropertyOfferResponse;
import com.sairo.be.domain.property.dto.response.PropertyUpdateResponse;
import com.sairo.be.domain.property.service.PropertyLifecycleService;
import com.sairo.be.domain.property.service.PropertyOfferService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.IfMatchVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/properties")
@Tag(name = "PROPERTY", description = "매물 거래제안, 카테고리 변경, 보관·삭제·복원")
public class PropertyLifecycleController {

  private final PropertyOfferService offerService;
  private final PropertyLifecycleService lifecycleService;

  public PropertyLifecycleController(
      PropertyOfferService offerService, PropertyLifecycleService lifecycleService) {
    this.offerService = offerService;
    this.lifecycleService = lifecycleService;
  }

  @Operation(summary = "거래제안 목록")
  @GetMapping("/{propertyId}/offers")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PropertyOfferListResponse listOffers(
      @PathVariable Long propertyId, @AuthenticationPrincipal StaffPrincipal principal) {
    return offerService.list(principal, propertyId);
  }

  @Operation(summary = "거래제안 등록", description = "같은 유형의 활성 제안은 하나만 둘 수 있다.")
  @PostMapping("/{propertyId}/offers")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  @ResponseStatus(HttpStatus.CREATED)
  public PropertyOfferResponse createOffer(
      @PathVariable Long propertyId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return offerService.create(principal, propertyId, body);
  }

  @Operation(summary = "거래제안 가격·상태 수정", description = "If-Match에 현재 매물 version을 보낸다.")
  @PatchMapping("/{propertyId}/offers/{offerId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PropertyOfferResponse updateOffer(
      @PathVariable Long propertyId,
      @PathVariable Long offerId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return offerService.update(principal, propertyId, offerId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(
      summary = "매물 카테고리 변경",
      description = "이전 카테고리의 필드값은 삭제하지 않고 보관한다. If-Match에 현재 version을 보낸다.")
  @PatchMapping("/{propertyId}/category")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PropertyUpdateResponse changeCategory(
      @PathVariable Long propertyId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return lifecycleService.changeCategory(
        principal, propertyId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(
      summary = "매물 보관 또는 삭제",
      description = "참조 없는 임시저장은 물리 삭제하고 그 밖에는 보관한다. If-Match는 보내면 검사한다.")
  @DeleteMapping("/{propertyId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable Long propertyId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal StaffPrincipal principal) {
    lifecycleService.delete(
        principal, propertyId, ifMatch == null ? null : IfMatchVersion.parse(ifMatch));
  }

  @Operation(summary = "보관된 매물 복원", description = "If-Match는 보내면 검사한다.")
  @PostMapping("/{propertyId}/restore")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PropertyUpdateResponse restore(
      @PathVariable Long propertyId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return lifecycleService.restore(
        principal, propertyId, ifMatch == null ? null : IfMatchVersion.parse(ifMatch));
  }
}
