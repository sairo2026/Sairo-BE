package com.sairo.be.domain.migration.repository;

import com.sairo.be.domain.migration.entity.ImportMappingItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportMappingItemRepository extends JpaRepository<ImportMappingItem, Long> {

  List<ImportMappingItem> findByMappingVersionIdOrderById(Long mappingVersionId);
}
