package com.sairo.be.domain.property.service;

import com.sairo.be.domain.property.dto.response.PropertyPhotoResponse;
import com.sairo.be.domain.property.entity.Property;
import com.sairo.be.domain.property.entity.PropertyPhoto;
import com.sairo.be.domain.property.mapper.PropertyMapper;
import com.sairo.be.domain.property.repository.PropertyPhotoRepository;
import com.sairo.be.domain.property.repository.PropertyRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.InputRules;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class PropertyPhotoService {

  public static final int MAX_PHOTOS = 20;
  private static final int DESCRIPTION_MAX = 500;

  private final PropertyRepository propertyRepository;
  private final PropertyPhotoRepository photoRepository;
  private final PropertyGuard guard;
  private final PropertyViewAssembler assembler;
  private final ObjectStorage objectStorage;
  private final Clock clock;

  public PropertyPhotoService(
      PropertyRepository propertyRepository,
      PropertyPhotoRepository photoRepository,
      PropertyGuard guard,
      PropertyViewAssembler assembler,
      ObjectStorage objectStorage,
      Clock clock) {
    this.propertyRepository = propertyRepository;
    this.photoRepository = photoRepository;
    this.guard = guard;
    this.assembler = assembler;
    this.objectStorage = objectStorage;
    this.clock = clock;
  }

  @Transactional
  public PropertyPhotoResponse upload(
      StaffPrincipal principal,
      Long propertyId,
      MultipartFile file,
      String descriptionInput,
      Integer sortOrderInput) {
    PhotoSanitizer.Sanitized sanitized = sanitize(file);
    String description = description(descriptionInput);
    Property property = guard.lockProperty(principal.officeId(), propertyId);
    guard.requireNotArchived(property);
    if (photoRepository.countByPropertyId(propertyId) >= MAX_PHOTOS) {
      throw invalid("file", FieldErrorCode.OUT_OF_RANGE, "사진은 매물당 20장까지 올릴 수 있습니다.");
    }
    int sortOrder =
        sortOrderInput != null ? sortOrderInput : photoRepository.findMaxSortOrder(propertyId) + 1;
    String key = "properties/" + propertyId + "/" + UUID.randomUUID() + "." + sanitized.extension();
    objectStorage.put(key, sanitized.content(), sanitized.contentType());
    try {
      PropertyPhoto photo =
          photoRepository.saveAndFlush(PropertyPhoto.of(propertyId, key, description, sortOrder));
      property.touch();
      Property saved = propertyRepository.saveAndFlush(property);
      return toResponse(photo, saved);
    } catch (RuntimeException e) {
      objectStorage.delete(key);
      throw e;
    }
  }

  @Transactional
  public PropertyPhotoResponse update(
      StaffPrincipal principal,
      Long propertyId,
      Long photoId,
      long expectedVersion,
      JsonNode body) {
    if (body == null || !body.isObject()) {
      throw invalid("body", FieldErrorCode.TYPE_MISMATCH, "JSON 객체여야 합니다.");
    }
    for (String name : body.propertyNames()) {
      if (!"description".equals(name) && !"sortOrder".equals(name)) {
        throw invalid(name, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 필드입니다.");
      }
    }
    if (!body.has("description") && !body.has("sortOrder")) {
      throw invalid("body", FieldErrorCode.REQUIRED, "description 또는 sortOrder를 보내야 합니다.");
    }
    Integer sortOrder = null;
    if (body.has("sortOrder")) {
      JsonNode node = body.get("sortOrder");
      if (!node.isIntegralNumber() || !node.canConvertToInt()) {
        throw invalid("sortOrder", FieldErrorCode.TYPE_MISMATCH, "정수여야 합니다.");
      }
      sortOrder = node.intValue();
    }
    String description = null;
    if (body.has("description") && !body.get("description").isNull()) {
      JsonNode node = body.get("description");
      if (!node.isString()) {
        throw invalid("description", FieldErrorCode.TYPE_MISMATCH, "문자열이어야 합니다.");
      }
      description = description(node.asString());
    }
    Property property = guard.lockProperty(principal.officeId(), propertyId);
    guard.requireNotArchived(property);
    guard.requireVersion(property, expectedVersion);
    PropertyPhoto photo =
        photoRepository
            .findByIdAndPropertyId(photoId, propertyId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (body.has("description")) {
      photo.changeDescription(description);
    }
    if (sortOrder != null) {
      photo.changeSortOrder(sortOrder);
    }
    PropertyPhoto saved = photoRepository.saveAndFlush(photo);
    property.touch();
    return toResponse(saved, propertyRepository.saveAndFlush(property));
  }

  @Transactional
  public void delete(StaffPrincipal principal, Long propertyId, Long photoId) {
    Property property = guard.lockProperty(principal.officeId(), propertyId);
    guard.requireNotArchived(property);
    PropertyPhoto photo =
        photoRepository
            .findByIdAndPropertyId(photoId, propertyId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    String key = photo.getObjectKey();
    photoRepository.delete(photo);
    property.touch();
    propertyRepository.saveAndFlush(property);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            objectStorage.delete(key);
          }
        });
  }

  private PropertyPhotoResponse toResponse(PropertyPhoto photo, Property property) {
    Instant expiresAt = clock.instant().plus(PropertyViewAssembler.PHOTO_URL_TTL);
    return PropertyMapper.toPhotoResponse(
        photo, property, assembler.signedUrl(photo.getObjectKey()), expiresAt);
  }

  private static PhotoSanitizer.Sanitized sanitize(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw invalid("file", FieldErrorCode.REQUIRED, "파일이 필요합니다.");
    }
    if (file.getSize() > PhotoSanitizer.MAX_BYTES) {
      throw invalid("file", FieldErrorCode.TOO_LONG, "파일은 10MB 이하여야 합니다.");
    }
    try {
      return PhotoSanitizer.sanitize(file.getBytes());
    } catch (IOException e) {
      throw invalid("file", FieldErrorCode.INVALID_FORMAT, "파일을 읽을 수 없습니다.");
    }
  }

  private static String description(String input) {
    if (input == null) {
      return null;
    }
    String normalized = InputRules.requireText("description", input, 0, DESCRIPTION_MAX);
    return normalized.isEmpty() ? null : normalized;
  }

  private static BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }
}
