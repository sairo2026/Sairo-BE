package com.sairo.be.domain.property.controller;

import com.sairo.be.domain.property.dto.request.PropertyListRequest;
import com.sairo.be.domain.property.dto.response.PropertyCreateResponse;
import com.sairo.be.domain.property.dto.response.PropertyDetailResponse;
import com.sairo.be.domain.property.dto.response.PropertyDuplicateCheckResponse;
import com.sairo.be.domain.property.dto.response.PropertyFieldValueResponse;
import com.sairo.be.domain.property.dto.response.PropertyListItemResponse;
import com.sairo.be.domain.property.dto.response.PropertyPhotoResponse;
import com.sairo.be.domain.property.dto.response.PropertyUpdateResponse;
import com.sairo.be.domain.property.service.PropertyCommandService;
import com.sairo.be.domain.property.service.PropertyFieldValueService;
import com.sairo.be.domain.property.service.PropertyPhotoService;
import com.sairo.be.domain.property.service.PropertyQueryService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.IfMatchVersion;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/properties")
@Tag(name = "PROPERTY", description = "매물 등록·조회·수정, 사진, 커스텀 필드 값")
public class PropertyController {

  private final PropertyQueryService queryService;
  private final PropertyCommandService commandService;
  private final PropertyPhotoService photoService;
  private final PropertyFieldValueService fieldValueService;

  public PropertyController(
      PropertyQueryService queryService,
      PropertyCommandService commandService,
      PropertyPhotoService photoService,
      PropertyFieldValueService fieldValueService) {
    this.queryService = queryService;
    this.commandService = commandService;
    this.photoService = photoService;
    this.fieldValueService = fieldValueService;
  }

  @Operation(summary = "매물 목록", description = "키워드, 카테고리, 거래유형, 가격·면적 범위, 담당자, 등록 단계, 보관 여부로 거른다.")
  @GetMapping
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PageResponse<PropertyListItemResponse> list(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) String dealType,
      @RequestParam(required = false) Long priceMin,
      @RequestParam(required = false) Long priceMax,
      @RequestParam(required = false) String areaType,
      @RequestParam(required = false) BigDecimal areaMin,
      @RequestParam(required = false) BigDecimal areaMax,
      @RequestParam(required = false) Long managingMembershipId,
      @RequestParam(required = false) String registrationStage,
      @RequestParam(required = false) Boolean archived,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.list(
        principal,
        new PropertyListRequest(
            keyword,
            categoryId,
            dealType,
            priceMin,
            priceMax,
            areaType,
            areaMin,
            areaMax,
            managingMembershipId,
            registrationStage,
            archived),
        PageQuery.of(page, size));
  }

  @Operation(summary = "매물 등록(임시저장)", description = "name과 categoryId만 있어도 DRAFT로 저장한다.")
  @PostMapping
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  @ResponseStatus(HttpStatus.CREATED)
  public PropertyCreateResponse create(
      @RequestBody JsonNode body, @AuthenticationPrincipal StaffPrincipal principal) {
    return commandService.create(principal, body);
  }

  @Operation(summary = "등록 전 유사 주소 매물 조회", description = "같은 사무소의 보관되지 않은 매물 중 같은 주소를 찾는다.")
  @GetMapping("/duplicate-check")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PropertyDuplicateCheckResponse duplicateCheck(
      @RequestParam String address,
      @RequestParam(required = false) String addressDetail,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.duplicateCheck(principal, address, addressDetail);
  }

  @Operation(summary = "매물 상세")
  @GetMapping("/{propertyId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PropertyDetailResponse detail(
      @PathVariable Long propertyId, @AuthenticationPrincipal StaffPrincipal principal) {
    return queryService.detail(principal, propertyId);
  }

  @Operation(
      summary = "매물 공통필드 수정",
      description = "If-Match에 현재 version을 보낸다. registrationStage=ACTIVE로 활성등록 전환한다.")
  @PatchMapping("/{propertyId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PropertyUpdateResponse update(
      @PathVariable Long propertyId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return commandService.update(principal, propertyId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(
      summary = "매물 사진 업로드",
      description = "JPG, PNG, WebP, 파일당 10MB 이하, 매물당 20장까지. EXIF를 제거한다.")
  @PostMapping(path = "/{propertyId}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  @ResponseStatus(HttpStatus.CREATED)
  public PropertyPhotoResponse uploadPhoto(
      @PathVariable Long propertyId,
      @RequestPart(value = "file", required = false) MultipartFile file,
      @RequestParam(required = false) String description,
      @RequestParam(required = false) Integer sortOrder,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return photoService.upload(principal, propertyId, file, description, sortOrder);
  }

  @Operation(summary = "매물 사진 설명·정렬 순서 수정", description = "If-Match에 현재 매물 version을 보낸다.")
  @PatchMapping("/{propertyId}/photos/{photoId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PropertyPhotoResponse updatePhoto(
      @PathVariable Long propertyId,
      @PathVariable Long photoId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return photoService.update(principal, propertyId, photoId, IfMatchVersion.parse(ifMatch), body);
  }

  @Operation(summary = "매물 사진 삭제")
  @DeleteMapping("/{propertyId}/photos/{photoId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deletePhoto(
      @PathVariable Long propertyId,
      @PathVariable Long photoId,
      @AuthenticationPrincipal StaffPrincipal principal) {
    photoService.delete(principal, propertyId, photoId);
  }

  @Operation(
      summary = "카테고리 전용 커스텀 필드 값 입력·수정",
      description = "value가 null이면 저장된 값을 비운다. If-Match에 현재 매물 version을 보낸다.")
  @PutMapping("/{propertyId}/fields/{fieldDefinitionId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PropertyFieldValueResponse putFieldValue(
      @PathVariable Long propertyId,
      @PathVariable Long fieldDefinitionId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return fieldValueService.put(
        principal, propertyId, fieldDefinitionId, IfMatchVersion.parse(ifMatch), body);
  }
}
