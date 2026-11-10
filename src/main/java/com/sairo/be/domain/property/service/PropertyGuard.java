package com.sairo.be.domain.property.service;

import com.sairo.be.domain.property.entity.Property;
import com.sairo.be.domain.property.entity.PropertyCategory;
import com.sairo.be.domain.property.repository.PropertyCategoryRepository;
import com.sairo.be.domain.property.repository.PropertyRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class PropertyGuard {

  private final PropertyRepository propertyRepository;
  private final PropertyCategoryRepository categoryRepository;
  private final ManagingMemberDirectory managingMemberDirectory;

  public PropertyGuard(
      PropertyRepository propertyRepository,
      PropertyCategoryRepository categoryRepository,
      ManagingMemberDirectory managingMemberDirectory) {
    this.propertyRepository = propertyRepository;
    this.categoryRepository = categoryRepository;
    this.managingMemberDirectory = managingMemberDirectory;
  }

  public Property lockProperty(Long officeId, Long propertyId) {
    return propertyRepository
        .findForUpdate(propertyId, officeId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  public void requireNotArchived(Property property) {
    if (property.isArchived()) {
      throw new BusinessException(ErrorCode.PROPERTY_ARCHIVED);
    }
  }

  public void requireVersion(Property property, long expectedVersion) {
    if (property.getVersion() != expectedVersion) {
      throw BusinessException.versionConflict(property.getVersion());
    }
  }

  public PropertyCategory requireVisibleCategory(Long officeId, Long categoryId) {
    return categoryRepository
        .findById(categoryId)
        .filter(category -> category.isVisibleTo(officeId))
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  public PropertyCategory requireUsableCategory(Long officeId, Long categoryId) {
    PropertyCategory category = requireVisibleCategory(officeId, categoryId);
    if (!category.isActive()) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(
              new FieldErrorItem(
                  "categoryId", FieldErrorCode.NOT_ALLOWED, "비활성 카테고리는 선택할 수 없습니다.")));
    }
    return category;
  }

  public void validateManagingMember(Long officeId, Long membershipId) {
    if (membershipId == null) {
      return;
    }
    ManagingMemberDirectory.ManagingMember member =
        managingMemberDirectory
            .find(membershipId)
            .filter(found -> found.officeId().equals(officeId))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!member.canManageProperty()) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(
              new FieldErrorItem(
                  "managingMembershipId",
                  FieldErrorCode.NOT_ALLOWED,
                  "승인된 대표 또는 소속공인중개사만 담당자로 지정할 수 있습니다.")));
    }
  }
}
