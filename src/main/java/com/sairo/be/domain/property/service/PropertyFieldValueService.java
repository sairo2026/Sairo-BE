package com.sairo.be.domain.property.service;

import com.sairo.be.domain.property.dto.response.PropertyFieldValueResponse;
import com.sairo.be.domain.property.entity.FieldType;
import com.sairo.be.domain.property.entity.NumberKind;
import com.sairo.be.domain.property.entity.Property;
import com.sairo.be.domain.property.entity.PropertyCustomFieldDefinition;
import com.sairo.be.domain.property.entity.PropertyFieldValue;
import com.sairo.be.domain.property.repository.PropertyCategoryRepository;
import com.sairo.be.domain.property.repository.PropertyCustomFieldDefinitionRepository;
import com.sairo.be.domain.property.repository.PropertyFieldValueRepository;
import com.sairo.be.domain.property.repository.PropertyRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.InputRules;
import com.sairo.be.global.support.TimeRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class PropertyFieldValueService {

  private static final int TEXT_MAX = 1_000;
  private static final int NUMBER_MAX_INTEGER_DIGITS = 18;

  private final PropertyRepository propertyRepository;
  private final PropertyCategoryRepository categoryRepository;
  private final PropertyCustomFieldDefinitionRepository definitionRepository;
  private final PropertyFieldValueRepository fieldValueRepository;
  private final PropertyGuard guard;

  public PropertyFieldValueService(
      PropertyRepository propertyRepository,
      PropertyCategoryRepository categoryRepository,
      PropertyCustomFieldDefinitionRepository definitionRepository,
      PropertyFieldValueRepository fieldValueRepository,
      PropertyGuard guard) {
    this.propertyRepository = propertyRepository;
    this.categoryRepository = categoryRepository;
    this.definitionRepository = definitionRepository;
    this.fieldValueRepository = fieldValueRepository;
    this.guard = guard;
  }

  @Transactional
  public PropertyFieldValueResponse put(
      StaffPrincipal principal,
      Long propertyId,
      Long fieldDefinitionId,
      long expectedVersion,
      JsonNode body) {
    JsonNode value = readValueNode(body);
    Property property = guard.lockProperty(principal.officeId(), propertyId);
    guard.requireNotArchived(property);
    guard.requireVersion(property, expectedVersion);
    PropertyCustomFieldDefinition definition =
        requireDefinition(principal.officeId(), fieldDefinitionId);
    if (!definition.getCategoryId().equals(property.getCategoryId())) {
      throw invalid("fieldDefinitionId", FieldErrorCode.NOT_ALLOWED, "매물 카테고리의 필드가 아닙니다.");
    }
    if (!definition.isActive()) {
      throw new BusinessException(ErrorCode.FIELD_INACTIVE);
    }
    Optional<PropertyFieldValue> existing =
        fieldValueRepository.findByPropertyIdAndFieldDefinitionId(propertyId, fieldDefinitionId);
    Object apiValue;
    boolean changed;
    if (value.isNull()) {
      boolean current = existing.filter(found -> !found.isArchived()).isPresent();
      changed = current;
      if (current) {
        fieldValueRepository.delete(existing.get());
      }
      apiValue = null;
    } else {
      PropertyFieldValue target =
          existing.orElseGet(() -> PropertyFieldValue.of(propertyId, fieldDefinitionId));
      boolean wasArchived = target.isArchived();
      Object before =
          existing
              .filter(found -> !found.isArchived())
              .map(found -> PropertyViewAssembler.apiValue(definition, found))
              .orElse(null);
      assign(definition, target, value);
      target.unarchive();
      apiValue = PropertyViewAssembler.apiValue(definition, target);
      changed = wasArchived || !Objects.equals(before, apiValue);
      fieldValueRepository.save(target);
    }
    if (changed) {
      property.touch();
    }
    fieldValueRepository.flush();
    Property saved = propertyRepository.saveAndFlush(property);
    return new PropertyFieldValueResponse(
        fieldDefinitionId,
        apiValue,
        saved.getOfficeId(),
        TimeRules.toDisplayOffset(saved.getUpdatedAt()),
        saved.getVersion());
  }

  private PropertyCustomFieldDefinition requireDefinition(Long officeId, Long fieldDefinitionId) {
    PropertyCustomFieldDefinition definition =
        definitionRepository
            .findById(fieldDefinitionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!definition.isSystem() && !definition.getOfficeId().equals(officeId)) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    categoryRepository
        .findById(definition.getCategoryId())
        .filter(found -> found.isVisibleTo(officeId))
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    return definition;
  }

  private static JsonNode readValueNode(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw invalid("body", FieldErrorCode.TYPE_MISMATCH, "JSON 객체여야 합니다.");
    }
    for (String name : body.propertyNames()) {
      if (!"value".equals(name)) {
        throw invalid(name, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 필드입니다.");
      }
    }
    if (!body.has("value")) {
      throw invalid("value", FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    return body.get("value");
  }

  private static void assign(
      PropertyCustomFieldDefinition definition, PropertyFieldValue target, JsonNode value) {
    FieldType type = definition.getFieldType();
    switch (type) {
      case TEXT -> {
        if (!value.isString()) {
          throw invalid("value", FieldErrorCode.TYPE_MISMATCH, "문자열이어야 합니다.");
        }
        target.assignText(InputRules.requireText("value", value.asString(), 1, TEXT_MAX));
      }
      case BOOLEAN -> {
        if (!value.isBoolean()) {
          throw invalid("value", FieldErrorCode.TYPE_MISMATCH, "true 또는 false여야 합니다.");
        }
        target.assignBoolean(value.booleanValue());
      }
      case DATE -> {
        if (!value.isString()) {
          throw invalid("value", FieldErrorCode.TYPE_MISMATCH, "날짜 문자열이어야 합니다.");
        }
        try {
          target.assignDate(LocalDate.parse(value.asString()));
        } catch (DateTimeParseException e) {
          throw invalid("value", FieldErrorCode.INVALID_FORMAT, "yyyy-MM-dd 형식이어야 합니다.");
        }
      }
      case NUMBER -> target.assignNumber(number(definition, value));
    }
  }

  private static BigDecimal number(PropertyCustomFieldDefinition definition, JsonNode value) {
    if (!value.isNumber()) {
      throw invalid("value", FieldErrorCode.TYPE_MISMATCH, "숫자여야 합니다.");
    }
    BigDecimal number = value.decimalValue();
    if (number.precision() - number.scale() > NUMBER_MAX_INTEGER_DIGITS) {
      throw invalid("value", FieldErrorCode.OUT_OF_RANGE, "허용 범위를 넘었습니다.");
    }
    if (definition.getNumberKind() == NumberKind.INTEGER
        && number.stripTrailingZeros().scale() > 0) {
      throw invalid("value", FieldErrorCode.INVALID_FORMAT, "정수여야 합니다.");
    }
    if (definition.getMinValue() != null && number.compareTo(definition.getMinValue()) < 0) {
      throw invalid("value", FieldErrorCode.OUT_OF_RANGE, "최솟값 미만입니다.");
    }
    if (definition.getMaxValue() != null && number.compareTo(definition.getMaxValue()) > 0) {
      throw invalid("value", FieldErrorCode.OUT_OF_RANGE, "최댓값을 넘었습니다.");
    }
    return number;
  }

  private static BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }
}
