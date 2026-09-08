package com.sairo.be.domain.property.service;

import com.sairo.be.domain.property.dto.request.PropertyCreateRequest;
import com.sairo.be.domain.property.dto.request.PropertyUpdateRequest;
import com.sairo.be.domain.property.dto.response.PropertyDetailResponse;
import com.sairo.be.domain.property.dto.response.PropertyDuplicateCheckResponse;
import com.sairo.be.domain.property.dto.response.PropertyListResponse;
import com.sairo.be.domain.property.entity.Property;
import com.sairo.be.domain.property.mapper.PropertyMapper;
import com.sairo.be.domain.property.repository.PropertyRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@RequiredArgsConstructor
public class PropertyService {

  private final PropertyRepository propertyRepository;

  @Transactional(readOnly = true)
  public PropertyListResponse listProperties(Long officeId) {
    var items =
        propertyRepository.findByOfficeIdOrderByIdAsc(officeId).stream()
            .map(PropertyMapper::toListItem)
            .toList();
    return new PropertyListResponse(items);
  }

  @Transactional(readOnly = true)
  public PropertyDuplicateCheckResponse checkDuplicate(Long officeId, String address) {
    var items =
        propertyRepository.findByOfficeIdAndAddress(officeId, address).stream()
            .map(PropertyMapper::toDuplicateItem)
            .toList();
    return new PropertyDuplicateCheckResponse(items);
  }

  @Transactional
  public PropertyDetailResponse registerProperty(Long officeId, PropertyCreateRequest request) {
    Property property =
        Property.register(
            officeId,
            request.address(),
            request.addressDetail(),
            request.propertyName(),
            request.dealType());
    propertyRepository.save(property);
    return PropertyMapper.toDetailResponse(property);
  }

  @Transactional(readOnly = true)
  public PropertyDetailResponse getProperty(Long officeId, Long propertyId) {
    return PropertyMapper.toDetailResponse(findOwnedProperty(officeId, propertyId));
  }

  @Transactional
  public PropertyDetailResponse updateProperty(
      Long officeId, Long propertyId, PropertyUpdateRequest request) {
    Property property = findOwnedProperty(officeId, propertyId);
    property.updateBasicInfo(request.address(), request.addressDetail(), request.propertyName());
    return PropertyMapper.toDetailResponse(property);
  }

  private Property findOwnedProperty(Long officeId, Long propertyId) {
    return propertyRepository
        .findByIdAndOfficeId(propertyId, officeId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }
}
