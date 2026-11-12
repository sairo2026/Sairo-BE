package com.sairo.be.domain.property.service;

import com.sairo.be.domain.property.dto.response.PropertyUpdateResponse;
import com.sairo.be.domain.property.entity.EntityLifecycleEvent;
import com.sairo.be.domain.property.entity.Property;
import com.sairo.be.domain.property.entity.PropertyCategory;
import com.sairo.be.domain.property.entity.PropertyFieldValue;
import com.sairo.be.domain.property.entity.PropertyPhoto;
import com.sairo.be.domain.property.entity.RegistrationStage;
import com.sairo.be.domain.property.mapper.PropertyMapper;
import com.sairo.be.domain.property.repository.EntityLifecycleEventRepository;
import com.sairo.be.domain.property.repository.PropertyArchiveRepository;
import com.sairo.be.domain.property.repository.PropertyFieldValueRepository;
import com.sairo.be.domain.property.repository.PropertyPhotoRepository;
import com.sairo.be.domain.property.repository.PropertyRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.port.ApplyGrantPort;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.global.security.StaffPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class PropertyLifecycleService {

  private final PropertyRepository propertyRepository;
  private final PropertyArchiveRepository archiveRepository;
  private final EntityLifecycleEventRepository lifecycleEventRepository;
  private final PropertyPhotoRepository photoRepository;
  private final PropertyFieldValueRepository fieldValueRepository;
  private final PropertyGuard guard;
  private final PropertyViewAssembler assembler;
  private final ManagingMemberDirectory managingMemberDirectory;
  private final ObjectStorage objectStorage;
  private final ApplyGrantPort applyGrants;
  private final Clock clock;

  public PropertyLifecycleService(
      PropertyRepository propertyRepository,
      PropertyArchiveRepository archiveRepository,
      EntityLifecycleEventRepository lifecycleEventRepository,
      PropertyPhotoRepository photoRepository,
      PropertyFieldValueRepository fieldValueRepository,
      PropertyGuard guard,
      PropertyViewAssembler assembler,
      ManagingMemberDirectory managingMemberDirectory,
      ObjectStorage objectStorage,
      ApplyGrantPort applyGrants,
      Clock clock) {
    this.propertyRepository = propertyRepository;
    this.archiveRepository = archiveRepository;
    this.lifecycleEventRepository = lifecycleEventRepository;
    this.photoRepository = photoRepository;
    this.fieldValueRepository = fieldValueRepository;
    this.guard = guard;
    this.assembler = assembler;
    this.managingMemberDirectory = managingMemberDirectory;
    this.objectStorage = objectStorage;
    this.applyGrants = applyGrants;
    this.clock = clock;
  }

  @Transactional
  public void delete(StaffPrincipal principal, Long propertyId, Long expectedVersion) {
    Property property = guard.lockProperty(principal.officeId(), propertyId);
    if (expectedVersion != null) {
      guard.requireVersion(property, expectedVersion);
    }
    guard.requireNotArchived(property);
    if (archiveRepository.hasActiveCoordination(principal.officeId(), propertyId)) {
      throw new BusinessException(ErrorCode.PROPERTY_HAS_ACTIVE_COORDINATION);
    }
    if (archiveRepository.hasSignedContract(principal.officeId(), propertyId)) {
      throw new BusinessException(ErrorCode.PROPERTY_HAS_ACTIVE_CONTRACT);
    }
    if (property.getRegistrationStage() == RegistrationStage.DRAFT
        && !archiveRepository.hasReferences(propertyId)) {
      List<String> keys =
          photoRepository.findByPropertyIdOrderBySortOrderAscIdAsc(propertyId).stream()
              .map(PropertyPhoto::getObjectKey)
              .toList();
      archiveRepository.deleteDraftRows(propertyId);
      afterCommit(() -> keys.forEach(objectStorage::delete));
      return;
    }
    Instant now = clock.instant();
    property.archive(now);
    applyGrants.revokeForArchivedProperty(propertyId);
    lifecycleEventRepository.save(
        EntityLifecycleEvent.propertyArchived(
            principal.officeId(), propertyId, now, principal.membershipId()));
    propertyRepository.saveAndFlush(property);
  }

  @Transactional
  public PropertyUpdateResponse restore(
      StaffPrincipal principal, Long propertyId, Long expectedVersion) {
    Property property = guard.lockProperty(principal.officeId(), propertyId);
    if (expectedVersion != null) {
      guard.requireVersion(property, expectedVersion);
    }
    if (!property.isArchived()) {
      throw new BusinessException(ErrorCode.PROPERTY_NOT_ARCHIVED);
    }
    boolean valid = property.getAddress() != null && managerValid(principal, property);
    property.restore(valid ? property.getRegistrationStage() : RegistrationStage.DRAFT);
    lifecycleEventRepository.save(
        EntityLifecycleEvent.propertyRestored(
            principal.officeId(), propertyId, clock.instant(), principal.membershipId()));
    Property saved = propertyRepository.saveAndFlush(property);
    return PropertyMapper.toUpdate(assembler.assemble(saved, false));
  }

  @Transactional
  public PropertyUpdateResponse changeCategory(
      StaffPrincipal principal, Long propertyId, long expectedVersion, JsonNode body) {
    Long newCategoryId = readCategoryId(body);
    Property property = guard.lockProperty(principal.officeId(), propertyId);
    guard.requireNotArchived(property);
    guard.requireVersion(property, expectedVersion);
    PropertyCategory target = guard.requireUsableCategory(principal.officeId(), newCategoryId);
    if (target.getId().equals(property.getCategoryId())) {
      return PropertyMapper.toUpdate(assembler.assemble(property, false));
    }
    Long previousCategoryId = property.getCategoryId();
    fieldValueRepository
        .findByPropertyIdAndArchived(propertyId, false)
        .forEach(PropertyFieldValue::archive);
    property.changeCategory(target.getId());
    lifecycleEventRepository.save(
        EntityLifecycleEvent.categoryChanged(
            principal.officeId(),
            propertyId,
            previousCategoryId,
            target.getId(),
            clock.instant(),
            principal.membershipId()));
    fieldValueRepository.flush();
    Property saved = propertyRepository.saveAndFlush(property);
    return PropertyMapper.toUpdate(assembler.assemble(saved, false));
  }

  private boolean managerValid(StaffPrincipal principal, Property property) {
    if (property.getManagingMembershipId() == null) {
      return false;
    }
    return managingMemberDirectory
        .find(property.getManagingMembershipId())
        .filter(member -> member.officeId().equals(principal.officeId()))
        .filter(ManagingMemberDirectory.ManagingMember::canManageProperty)
        .isPresent();
  }

  private static Long readCategoryId(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw invalid("body", FieldErrorCode.TYPE_MISMATCH, "JSON 객체여야 합니다.");
    }
    for (String name : body.propertyNames()) {
      if (!"categoryId".equals(name)) {
        throw invalid(name, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 필드입니다.");
      }
    }
    JsonNode node = body.get("categoryId");
    if (node == null || node.isNull()) {
      throw invalid("categoryId", FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    if (!node.isIntegralNumber() || !node.canConvertToLong()) {
      throw invalid("categoryId", FieldErrorCode.TYPE_MISMATCH, "정수여야 합니다.");
    }
    return node.longValue();
  }

  private static void afterCommit(Runnable action) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
  }

  private static BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }
}
