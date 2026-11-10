package com.sairo.be.domain.property.service;

import com.sairo.be.domain.property.dto.request.PropertyCategoryCreateRequest;
import com.sairo.be.domain.property.dto.request.PropertyCategoryUpdateRequest;
import com.sairo.be.domain.property.dto.request.PropertyFieldDefinitionCreateRequest;
import com.sairo.be.domain.property.dto.request.PropertyFieldDefinitionUpdateRequest;
import com.sairo.be.domain.property.dto.response.PropertyCategoryListResponse;
import com.sairo.be.domain.property.dto.response.PropertyCategoryResponse;
import com.sairo.be.domain.property.dto.response.PropertyFieldDefinitionListResponse;
import com.sairo.be.domain.property.dto.response.PropertyFieldDefinitionResponse;
import com.sairo.be.domain.property.entity.PropertyCategory;
import com.sairo.be.domain.property.entity.PropertyCustomFieldDefinition;
import com.sairo.be.domain.property.mapper.PropertyMapper;
import com.sairo.be.domain.property.repository.PropertyCategoryRepository;
import com.sairo.be.domain.property.repository.PropertyCustomFieldDefinitionRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.InputRules;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class PropertyCategoryService {

  public static final int CUSTOM_CATEGORY_LIMIT = 20;
  public static final int FIELD_LIMIT = 50;
  private static final int CATEGORY_NAME_MAX = 50;
  private static final long NO_EXCLUDE = -1L;

  private final PropertyCategoryRepository categoryRepository;
  private final PropertyCustomFieldDefinitionRepository definitionRepository;
  private final PropertyGuard guard;
  private final AdvisoryLock advisoryLock;

  public PropertyCategoryService(
      PropertyCategoryRepository categoryRepository,
      PropertyCustomFieldDefinitionRepository definitionRepository,
      PropertyGuard guard,
      AdvisoryLock advisoryLock) {
    this.categoryRepository = categoryRepository;
    this.definitionRepository = definitionRepository;
    this.guard = guard;
    this.advisoryLock = advisoryLock;
  }

  @Transactional(readOnly = true)
  public PropertyCategoryListResponse list(StaffPrincipal principal) {
    return PropertyMapper.toCategoryList(categoryRepository.findVisible(principal.officeId()));
  }

  @Transactional
  public PropertyCategoryResponse create(
      StaffPrincipal principal, PropertyCategoryCreateRequest request) {
    String name = InputRules.requireText("name", request.name(), 1, CATEGORY_NAME_MAX);
    advisoryLock.lockForTransaction(categoryLockKey(principal.officeId()));
    if (categoryRepository.existsVisibleName(principal.officeId(), name, NO_EXCLUDE)) {
      throw new BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATED);
    }
    requireCategoryCapacity(principal.officeId());
    PropertyCategory saved =
        categoryRepository.saveAndFlush(PropertyCategory.custom(principal.officeId(), name));
    return PropertyMapper.toCategoryResponse(saved);
  }

  @Transactional
  public PropertyCategoryResponse update(
      StaffPrincipal principal, Long categoryId, PropertyCategoryUpdateRequest request) {
    if (request.name() == null && request.active() == null) {
      throw invalid("body", FieldErrorCode.REQUIRED, "name 또는 active를 보내야 합니다.");
    }
    String name =
        request.name() == null
            ? null
            : InputRules.requireText("name", request.name(), 1, CATEGORY_NAME_MAX);
    PropertyCategory category = guard.requireVisibleCategory(principal.officeId(), categoryId);
    if (category.isSystem()) {
      throw new BusinessException(ErrorCode.SYSTEM_CATEGORY_IMMUTABLE);
    }
    advisoryLock.lockForTransaction(categoryLockKey(principal.officeId()));
    if (name != null && !name.equals(category.getName())) {
      if (categoryRepository.existsVisibleName(principal.officeId(), name, category.getId())) {
        throw new BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATED);
      }
      category.rename(name);
    }
    if (request.active() != null && request.active() != category.isActive()) {
      if (request.active()) {
        requireCategoryCapacity(principal.officeId());
      }
      category.changeActive(request.active());
    }
    return PropertyMapper.toCategoryResponse(categoryRepository.saveAndFlush(category));
  }

  @Transactional(readOnly = true)
  public PropertyFieldDefinitionListResponse listFields(StaffPrincipal principal, Long categoryId) {
    PropertyCategory category = guard.requireVisibleCategory(principal.officeId(), categoryId);
    return PropertyMapper.toFieldDefinitionList(
        definitionRepository.findVisibleByCategory(category.getId(), principal.officeId()));
  }

  @Transactional
  public PropertyFieldDefinitionResponse createField(
      StaffPrincipal principal, Long categoryId, PropertyFieldDefinitionCreateRequest request) {
    String fieldName =
        InputRules.requireText(
            "fieldName", request.fieldName(), 1, InputRules.CUSTOM_FIELD_NAME_MAX);
    PropertyCategory category = guard.requireVisibleCategory(principal.officeId(), categoryId);
    advisoryLock.lockForTransaction(fieldLockKey(category.getId()));
    if (definitionRepository.existsByCategoryIdAndFieldNameAndIdNot(
        category.getId(), fieldName, NO_EXCLUDE)) {
      throw new BusinessException(ErrorCode.FIELD_NAME_DUPLICATED);
    }
    requireFieldCapacity(category.getId(), principal.officeId());
    PropertyCustomFieldDefinition saved =
        definitionRepository.saveAndFlush(
            PropertyCustomFieldDefinition.custom(
                category.getId(), principal.officeId(), fieldName, request.fieldType()));
    return PropertyMapper.toFieldDefinitionResponse(saved);
  }

  @Transactional
  public PropertyFieldDefinitionResponse updateField(
      StaffPrincipal principal,
      Long categoryId,
      Long fieldDefinitionId,
      PropertyFieldDefinitionUpdateRequest request) {
    if (request.fieldName() == null && request.active() == null) {
      throw invalid("body", FieldErrorCode.REQUIRED, "fieldName 또는 active를 보내야 합니다.");
    }
    String fieldName =
        request.fieldName() == null
            ? null
            : InputRules.requireText(
                "fieldName", request.fieldName(), 1, InputRules.CUSTOM_FIELD_NAME_MAX);
    PropertyCategory category = guard.requireVisibleCategory(principal.officeId(), categoryId);
    PropertyCustomFieldDefinition definition =
        definitionRepository
            .findById(fieldDefinitionId)
            .filter(found -> found.getCategoryId().equals(category.getId()))
            .filter(found -> found.isSystem() || found.getOfficeId().equals(principal.officeId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (definition.isSystem()) {
      throw new BusinessException(ErrorCode.SYSTEM_CATEGORY_IMMUTABLE);
    }
    advisoryLock.lockForTransaction(fieldLockKey(category.getId()));
    if (fieldName != null && !fieldName.equals(definition.getFieldName())) {
      if (definitionRepository.existsByCategoryIdAndFieldNameAndIdNot(
          category.getId(), fieldName, definition.getId())) {
        throw new BusinessException(ErrorCode.FIELD_NAME_DUPLICATED);
      }
      definition.rename(fieldName);
    }
    if (request.active() != null && request.active() != definition.isActive()) {
      if (request.active()) {
        requireFieldCapacity(category.getId(), principal.officeId());
      }
      definition.changeActive(request.active());
    }
    return PropertyMapper.toFieldDefinitionResponse(definitionRepository.saveAndFlush(definition));
  }

  private void requireCategoryCapacity(Long officeId) {
    if (categoryRepository.countByOfficeIdAndSystemFalseAndActiveTrue(officeId)
        >= CUSTOM_CATEGORY_LIMIT) {
      throw new BusinessException(ErrorCode.CATEGORY_LIMIT_EXCEEDED);
    }
  }

  private void requireFieldCapacity(Long categoryId, Long officeId) {
    if (definitionRepository.countVisibleActive(categoryId, officeId) >= FIELD_LIMIT) {
      throw new BusinessException(ErrorCode.FIELD_LIMIT_EXCEEDED);
    }
  }

  private static String categoryLockKey(Long officeId) {
    return "property-category:" + officeId;
  }

  private static String fieldLockKey(Long categoryId) {
    return "property-field:" + categoryId;
  }

  private static BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }
}
