package com.sairo.be.domain.property.repository;

import com.sairo.be.domain.property.entity.PropertyCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PropertyCategoryRepository extends JpaRepository<PropertyCategory, Long> {

  @Query(
      "select c from PropertyCategory c where c.officeId is null or c.officeId = :officeId"
          + " order by c.system desc, c.id")
  List<PropertyCategory> findVisible(@Param("officeId") Long officeId);

  long countByOfficeIdAndSystemFalseAndActiveTrue(Long officeId);

  @Query(
      "select count(c) > 0 from PropertyCategory c where (c.officeId is null or c.officeId ="
          + " :officeId) and c.name = :name and c.id <> :excludeId")
  boolean existsVisibleName(
      @Param("officeId") Long officeId,
      @Param("name") String name,
      @Param("excludeId") Long excludeId);
}
