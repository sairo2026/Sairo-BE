package com.sairo.be.domain.property.controller;

import com.sairo.be.domain.property.dto.request.PropertyCategoryCreateRequest;
import com.sairo.be.domain.property.dto.request.PropertyCategoryUpdateRequest;
import com.sairo.be.domain.property.dto.request.PropertyFieldDefinitionCreateRequest;
import com.sairo.be.domain.property.dto.request.PropertyFieldDefinitionUpdateRequest;
import com.sairo.be.domain.property.dto.response.PropertyCategoryListResponse;
import com.sairo.be.domain.property.dto.response.PropertyCategoryResponse;
import com.sairo.be.domain.property.dto.response.PropertyFieldDefinitionListResponse;
import com.sairo.be.domain.property.dto.response.PropertyFieldDefinitionResponse;
import com.sairo.be.domain.property.service.PropertyCategoryService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/property-categories")
@Tag(name = "PROPERTY", description = "매물 카테고리와 커스텀 필드 정의")
public class PropertyCategoryController {

  private final PropertyCategoryService categoryService;

  public PropertyCategoryController(PropertyCategoryService categoryService) {
    this.categoryService = categoryService;
  }

  @Operation(summary = "카테고리 목록", description = "기본 10종과 사무소 커스텀 카테고리를 함께 돌려준다.")
  @GetMapping
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PropertyCategoryListResponse list(@AuthenticationPrincipal StaffPrincipal principal) {
    return categoryService.list(principal);
  }

  @Operation(summary = "커스텀 카테고리 추가", description = "사무소당 활성 20개까지 허용한다.")
  @PostMapping
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  @ResponseStatus(HttpStatus.CREATED)
  public PropertyCategoryResponse create(
      @Valid @RequestBody PropertyCategoryCreateRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return categoryService.create(principal, request);
  }

  @Operation(summary = "커스텀 카테고리 이름 수정·비활성화", description = "기본 10종은 바꿀 수 없다.")
  @PatchMapping("/{categoryId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PropertyCategoryResponse update(
      @PathVariable Long categoryId,
      @RequestBody PropertyCategoryUpdateRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return categoryService.update(principal, categoryId, request);
  }

  @Operation(summary = "카테고리별 커스텀 필드 정의 목록")
  @GetMapping("/{categoryId}/fields")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PropertyFieldDefinitionListResponse listFields(
      @PathVariable Long categoryId, @AuthenticationPrincipal StaffPrincipal principal) {
    return categoryService.listFields(principal, categoryId);
  }

  @Operation(summary = "커스텀 필드 정의 추가", description = "카테고리당 활성 50개까지 허용한다.")
  @PostMapping("/{categoryId}/fields")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  @ResponseStatus(HttpStatus.CREATED)
  public PropertyFieldDefinitionResponse createField(
      @PathVariable Long categoryId,
      @Valid @RequestBody PropertyFieldDefinitionCreateRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return categoryService.createField(principal, categoryId, request);
  }

  @Operation(summary = "커스텀 필드 이름 수정·비활성화", description = "타입 변경과 물리삭제는 할 수 없다.")
  @PatchMapping("/{categoryId}/fields/{fieldDefinitionId}")
  @RequiresAccess(AccessPermission.AGENT_OR_OWNER)
  public PropertyFieldDefinitionResponse updateField(
      @PathVariable Long categoryId,
      @PathVariable Long fieldDefinitionId,
      @RequestBody PropertyFieldDefinitionUpdateRequest request,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return categoryService.updateField(principal, categoryId, fieldDefinitionId, request);
  }
}
