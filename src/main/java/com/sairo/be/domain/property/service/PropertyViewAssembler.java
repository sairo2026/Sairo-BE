package com.sairo.be.domain.property.service;

import com.sairo.be.domain.property.entity.FieldType;
import com.sairo.be.domain.property.entity.NumberKind;
import com.sairo.be.domain.property.entity.Property;
import com.sairo.be.domain.property.entity.PropertyCategory;
import com.sairo.be.domain.property.entity.PropertyCustomFieldDefinition;
import com.sairo.be.domain.property.entity.PropertyFieldValue;
import com.sairo.be.domain.property.entity.PropertyPhoto;
import com.sairo.be.domain.property.mapper.PropertyView;
import com.sairo.be.domain.property.repository.PropertyCategoryRepository;
import com.sairo.be.domain.property.repository.PropertyCustomFieldDefinitionRepository;
import com.sairo.be.domain.property.repository.PropertyFieldValueRepository;
import com.sairo.be.domain.property.repository.PropertyPhotoRepository;
import com.sairo.be.domain.property.repository.PropertyReadRepository;
import com.sairo.be.global.port.ObjectStorage;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class PropertyViewAssembler {

  public static final Duration PHOTO_URL_TTL = Duration.ofMinutes(10);

  private static final String LEASE_SOURCE_CONTRACT = "CONTRACT";
  private static final String LEASE_SOURCE_REFERENCE = "REFERENCE";

  private final PropertyCategoryRepository categoryRepository;
  private final PropertyCustomFieldDefinitionRepository definitionRepository;
  private final PropertyFieldValueRepository fieldValueRepository;
  private final PropertyPhotoRepository photoRepository;
  private final PropertyReadRepository readRepository;
  private final ManagingMemberDirectory managingMemberDirectory;
  private final ObjectStorage objectStorage;
  private final Clock clock;

  public PropertyViewAssembler(
      PropertyCategoryRepository categoryRepository,
      PropertyCustomFieldDefinitionRepository definitionRepository,
      PropertyFieldValueRepository fieldValueRepository,
      PropertyPhotoRepository photoRepository,
      PropertyReadRepository readRepository,
      ManagingMemberDirectory managingMemberDirectory,
      ObjectStorage objectStorage,
      Clock clock) {
    this.categoryRepository = categoryRepository;
    this.definitionRepository = definitionRepository;
    this.fieldValueRepository = fieldValueRepository;
    this.photoRepository = photoRepository;
    this.readRepository = readRepository;
    this.managingMemberDirectory = managingMemberDirectory;
    this.objectStorage = objectStorage;
    this.clock = clock;
  }

  public PropertyView assemble(Property property, boolean detail) {
    long propertyId = property.getId();
    long officeId = property.getOfficeId();
    String categoryName =
        categoryRepository
            .findById(property.getCategoryId())
            .map(PropertyCategory::getName)
            .orElse(null);
    String managerName =
        property.getManagingMembershipId() == null
            ? null
            : managingMemberDirectory
                .find(property.getManagingMembershipId())
                .map(ManagingMemberDirectory.ManagingMember::displayName)
                .orElse(null);
    List<PropertyPhoto> photos =
        photoRepository.findByPropertyIdOrderBySortOrderAscIdAsc(propertyId);
    Map<Long, String> urls = new HashMap<>();
    Instant expiresAt = clock.instant().plus(PHOTO_URL_TTL);
    if (detail) {
      photos.forEach(photo -> urls.put(photo.getId(), signedUrl(photo.getObjectKey())));
    }
    List<PropertyFieldValue> stored =
        fieldValueRepository.findByPropertyIdAndArchived(propertyId, false);
    List<PropertyFieldValue> archived =
        fieldValueRepository.findByPropertyIdAndArchived(propertyId, true);
    Set<Long> definitionIds = new HashSet<>();
    stored.forEach(value -> definitionIds.add(value.getFieldDefinitionId()));
    archived.forEach(value -> definitionIds.add(value.getFieldDefinitionId()));
    Map<Long, PropertyCustomFieldDefinition> definitions = new HashMap<>();
    definitionRepository
        .findByIdIn(definitionIds)
        .forEach(def -> definitions.put(def.getId(), def));
    Map<Long, String> categoryNames = new HashMap<>();
    categoryRepository
        .findAllById(
            definitions.values().stream()
                .map(PropertyCustomFieldDefinition::getCategoryId)
                .toList())
        .forEach(category -> categoryNames.put(category.getId(), category.getName()));
    List<PropertyView.FieldView> fields =
        stored.stream()
            .map(value -> fieldView(value, definitions, categoryNames, false))
            .sorted(Comparator.comparing(view -> view.definition().getId()))
            .toList();
    List<PropertyView.FieldView> archivedFields =
        archived.stream()
            .map(value -> fieldView(value, definitions, categoryNames, true))
            .sorted(Comparator.comparing(view -> view.definition().getId()))
            .toList();
    List<PropertyReadRepository.OfferRow> offers =
        readRepository.findOffers(List.of(propertyId)).getOrDefault(propertyId, List.of());
    LocalDate contractEnd =
        readRepository.findContractLeaseEnds(List.of(propertyId)).get(propertyId);
    LocalDate leaseEnd = contractEnd != null ? contractEnd : property.getReferenceLeaseEndDate();
    String leaseSource =
        contractEnd != null
            ? LEASE_SOURCE_CONTRACT
            : property.getReferenceLeaseEndDate() != null ? LEASE_SOURCE_REFERENCE : null;
    PropertyView.Linked linked =
        detail
            ? new PropertyView.Linked(
                readRepository.findActiveCoordinations(officeId, propertyId),
                readRepository.findOwnerOccupants(propertyId),
                readRepository.findVisits(officeId, propertyId),
                readRepository.findContracts(officeId, propertyId))
            : null;
    return new PropertyView(
        property,
        categoryName,
        managerName,
        photos,
        urls,
        expiresAt,
        fields,
        archivedFields,
        offers,
        leaseEnd,
        leaseSource,
        linked);
  }

  public String signedUrl(String objectKey) {
    return objectStorage.issueDownloadUrl(objectKey, PHOTO_URL_TTL).toString();
  }

  private PropertyView.FieldView fieldView(
      PropertyFieldValue stored,
      Map<Long, PropertyCustomFieldDefinition> definitions,
      Map<Long, String> categoryNames,
      boolean archived) {
    PropertyCustomFieldDefinition definition = definitions.get(stored.getFieldDefinitionId());
    return new PropertyView.FieldView(
        definition,
        apiValue(definition, stored),
        archived ? definition.getCategoryId() : null,
        archived ? categoryNames.get(definition.getCategoryId()) : null);
  }

  public static Object apiValue(
      PropertyCustomFieldDefinition definition, PropertyFieldValue stored) {
    FieldType type = definition.getFieldType();
    return switch (type) {
      case TEXT -> stored.getValueText();
      case BOOLEAN -> stored.getValueBoolean();
      case DATE -> stored.getValueDate();
      case NUMBER -> numberValue(definition, stored.getValueNumber());
    };
  }

  private static Object numberValue(PropertyCustomFieldDefinition definition, BigDecimal number) {
    if (number == null) {
      return null;
    }
    if (definition.getNumberKind() == NumberKind.INTEGER) {
      return number.longValue();
    }
    BigDecimal stripped = number.stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
  }
}
