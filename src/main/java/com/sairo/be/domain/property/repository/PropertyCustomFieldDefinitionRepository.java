package com.sairo.be.domain.property.repository;

import com.sairo.be.domain.property.entity.PropertyCustomFieldDefinition;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PropertyCustomFieldDefinitionRepository
    extends JpaRepository<PropertyCustomFieldDefinition, Long> {

  @Query(
      "select d from PropertyCustomFieldDefinition d where d.categoryId = :categoryId and"
          + " (d.officeId is null or d.officeId = :officeId) order by d.id")
  List<PropertyCustomFieldDefinition> findVisibleByCategory(
      @Param("categoryId") Long categoryId, @Param("officeId") Long officeId);

  List<PropertyCustomFieldDefinition> findByIdIn(Collection<Long> ids);

  @Query(
      "select count(d) from PropertyCustomFieldDefinition d where d.categoryId = :categoryId and"
          + " d.active = true and (d.officeId is null or d.officeId = :officeId)")
  long countVisibleActive(@Param("categoryId") Long categoryId, @Param("officeId") Long officeId);

  boolean existsByCategoryIdAndFieldNameAndIdNot(Long categoryId, String fieldName, Long excludeId);
}
