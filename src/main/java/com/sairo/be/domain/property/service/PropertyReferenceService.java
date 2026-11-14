package com.sairo.be.domain.property.service;

import com.sairo.be.domain.property.entity.Property;
import com.sairo.be.domain.property.repository.PropertyRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class PropertyReferenceService {

  public record PropertySummary(
      Long propertyId,
      Long officeId,
      String name,
      String address,
      String addressDetail,
      boolean archived) {

    public String district() {
      if (address == null || address.isBlank()) {
        return "";
      }
      String[] tokens = address.strip().split("\s+");
      return tokens.length < 2 ? tokens[0] : tokens[0] + " " + tokens[1];
    }
  }

  private final PropertyRepository propertyRepository;

  public PropertyReferenceService(PropertyRepository propertyRepository) {
    this.propertyRepository = propertyRepository;
  }

  public Optional<PropertySummary> find(Long propertyId) {
    return propertyRepository.findById(propertyId).map(PropertyReferenceService::summary);
  }

  public Map<Long, PropertySummary> findAll(Collection<Long> propertyIds) {
    Map<Long, PropertySummary> result = new LinkedHashMap<>();
    for (Property property : propertyRepository.findAllById(propertyIds)) {
      result.put(property.getId(), summary(property));
    }
    return result;
  }

  private static PropertySummary summary(Property property) {
    return new PropertySummary(
        property.getId(),
        property.getOfficeId(),
        property.getName(),
        property.getAddress(),
        property.getAddressDetail(),
        property.isArchived());
  }
}
