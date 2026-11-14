package com.sairo.be.domain.property.service;

import com.sairo.be.domain.property.dto.response.PropertyCreateResponse;
import com.sairo.be.domain.property.dto.response.PropertyUpdateResponse;
import com.sairo.be.domain.property.entity.EntityLifecycleEvent;
import com.sairo.be.domain.property.entity.LifecycleCreationSource;
import com.sairo.be.domain.property.entity.Property;
import com.sairo.be.domain.property.entity.PropertyAttributes;
import com.sairo.be.domain.property.entity.PropertyCategory;
import com.sairo.be.domain.property.mapper.PropertyMapper;
import com.sairo.be.domain.property.repository.EntityLifecycleEventRepository;
import com.sairo.be.domain.property.repository.PropertyReadRepository;
import com.sairo.be.domain.property.repository.PropertyRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.StaffPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class PropertyCommandService {

  public static final String IMPORT_DUPLICATE_REASON = "데이터 이전으로 등록한 매물";

  private final PropertyRepository propertyRepository;
  private final EntityLifecycleEventRepository lifecycleEventRepository;
  private final PropertyReadRepository readRepository;
  private final PropertyGuard guard;
  private final PropertyViewAssembler assembler;

  public PropertyCommandService(
      PropertyRepository propertyRepository,
      EntityLifecycleEventRepository lifecycleEventRepository,
      PropertyReadRepository readRepository,
      PropertyGuard guard,
      PropertyViewAssembler assembler) {
    this.propertyRepository = propertyRepository;
    this.lifecycleEventRepository = lifecycleEventRepository;
    this.readRepository = readRepository;
    this.guard = guard;
    this.assembler = assembler;
  }

  @Transactional
  public PropertyCreateResponse create(StaffPrincipal principal, JsonNode body) {
    return register(principal, body, LifecycleCreationSource.MANUAL);
  }

  @Transactional
  public PropertyCreateResponse createImported(StaffPrincipal principal, JsonNode body) {
    return register(principal, body, LifecycleCreationSource.IMPORT);
  }

  private PropertyCreateResponse register(
      StaffPrincipal principal, JsonNode body, LifecycleCreationSource creationSource) {
    PropertyInputParser.Parsed parsed = PropertyInputParser.parseCreate(body);
    PropertyAttributes attributes = PropertyInputParser.initial(parsed.values());
    PropertyInputParser.validateCombination(attributes);
    PropertyCategory category =
        guard.requireUsableCategory(principal.officeId(), parsed.categoryId());
    guard.validateManagingMember(principal.officeId(), attributes.managingMembershipId());
    if (creationSource == LifecycleCreationSource.IMPORT) {
      attributes = withImportDuplicateReason(principal.officeId(), attributes);
    } else {
      requireDuplicateReason(principal.officeId(), attributes, null);
    }
    Property property =
        propertyRepository.saveAndFlush(
            Property.draft(principal.officeId(), category.getId(), attributes));
    lifecycleEventRepository.save(
        EntityLifecycleEvent.propertyCreated(
            principal.officeId(),
            property.getId(),
            property.getCreatedAt(),
            principal.membershipId(),
            creationSource));
    return PropertyMapper.toCreateResponse(property);
  }

  private PropertyAttributes withImportDuplicateReason(
      Long officeId, PropertyAttributes attributes) {
    if (attributes.address() == null || attributes.duplicateOverrideReason() != null) {
      return attributes;
    }
    boolean duplicated =
        !readRepository
            .findDuplicates(officeId, attributes.address(), attributes.addressDetail(), null, true)
            .isEmpty();
    if (!duplicated) {
      return attributes;
    }
    return PropertyInputParser.merge(
        attributes, Map.of("duplicateOverrideReason", IMPORT_DUPLICATE_REASON));
  }

  @Transactional
  public PropertyUpdateResponse update(
      StaffPrincipal principal, Long propertyId, long expectedVersion, JsonNode body) {
    PropertyInputParser.Parsed parsed = PropertyInputParser.parsePatch(body);
    Property property = guard.lockProperty(principal.officeId(), propertyId);
    guard.requireNotArchived(property);
    guard.requireVersion(property, expectedVersion);
    PropertyAttributes current = property.attributes();
    PropertyAttributes merged = PropertyInputParser.merge(current, parsed.values());
    PropertyInputParser.validateCombination(merged);
    boolean becomesActive = parsed.activate() || property.isActive();
    if (becomesActive && (merged.address() == null || merged.managingMembershipId() == null)) {
      throw new BusinessException(ErrorCode.INCOMPLETE_FOR_ACTIVATION);
    }
    if (parsed.has("managingMembershipId")
        && !Objects.equals(current.managingMembershipId(), merged.managingMembershipId())) {
      guard.validateManagingMember(principal.officeId(), merged.managingMembershipId());
    }
    if (parsed.has("address") || parsed.has("addressDetail")) {
      requireDuplicateReason(principal.officeId(), merged, property.getId());
    }
    property.applyAttributes(merged);
    if (parsed.activate()) {
      property.activate();
    }
    Property saved = propertyRepository.saveAndFlush(property);
    return PropertyMapper.toUpdate(assembler.assemble(saved, false));
  }

  private void requireDuplicateReason(
      Long officeId, PropertyAttributes attributes, Long excludePropertyId) {
    if (attributes.address() == null || attributes.duplicateOverrideReason() != null) {
      return;
    }
    boolean duplicated =
        !readRepository
            .findDuplicates(
                officeId, attributes.address(), attributes.addressDetail(), excludePropertyId, true)
            .isEmpty();
    if (duplicated) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(
              new FieldErrorItem(
                  "duplicateOverrideReason",
                  FieldErrorCode.REQUIRED,
                  "같은 주소의 매물이 있어 별도 등록 사유가 필요합니다.")));
    }
  }
}
