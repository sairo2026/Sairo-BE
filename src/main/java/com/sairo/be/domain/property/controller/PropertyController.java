package com.sairo.be.domain.property.controller;

import com.sairo.be.domain.property.dto.request.PropertyCreateRequest;
import com.sairo.be.domain.property.dto.request.PropertyUpdateRequest;
import com.sairo.be.domain.property.dto.response.PropertyDetailResponse;
import com.sairo.be.domain.property.dto.response.PropertyDuplicateCheckResponse;
import com.sairo.be.domain.property.dto.response.PropertyListResponse;
import com.sairo.be.domain.property.service.PropertyService;
import com.sairo.be.global.security.StaffPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/properties")
@RequiredArgsConstructor
public class PropertyController {

  private final PropertyService propertyService;

  @GetMapping
  public PropertyListResponse list(@AuthenticationPrincipal StaffPrincipal principal) {
    return propertyService.listProperties(principal.officeId());
  }

  @GetMapping("/duplicate-check")
  public PropertyDuplicateCheckResponse duplicateCheck(
      @AuthenticationPrincipal StaffPrincipal principal, @RequestParam String address) {
    return propertyService.checkDuplicate(principal.officeId(), address);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public PropertyDetailResponse create(
      @AuthenticationPrincipal StaffPrincipal principal,
      @Valid @RequestBody PropertyCreateRequest request) {
    return propertyService.registerProperty(principal.officeId(), request);
  }

  @GetMapping("/{propertyId}")
  public PropertyDetailResponse detail(
      @AuthenticationPrincipal StaffPrincipal principal, @PathVariable Long propertyId) {
    return propertyService.getProperty(principal.officeId(), propertyId);
  }

  @PatchMapping("/{propertyId}")
  public PropertyDetailResponse update(
      @AuthenticationPrincipal StaffPrincipal principal,
      @PathVariable Long propertyId,
      @Valid @RequestBody PropertyUpdateRequest request) {
    return propertyService.updateProperty(principal.officeId(), propertyId, request);
  }
}
