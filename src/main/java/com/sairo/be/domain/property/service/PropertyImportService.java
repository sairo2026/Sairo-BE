package com.sairo.be.domain.property.service;

import com.sairo.be.domain.property.repository.PropertyArchiveRepository;
import com.sairo.be.domain.property.repository.PropertyImportRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.ImportedRecordState;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

@Profile("!migrate")
@Service
public class PropertyImportService {

  public record MatchCandidate(
      long propertyId, String normalizedAddress, String normalizedAddressDetail) {}

  private final PropertyCommandService commandService;
  private final PropertyImportRepository importRepository;
  private final PropertyArchiveRepository archiveRepository;

  public PropertyImportService(
      PropertyCommandService commandService,
      PropertyImportRepository importRepository,
      PropertyArchiveRepository archiveRepository) {
    this.commandService = commandService;
    this.importRepository = importRepository;
    this.archiveRepository = archiveRepository;
  }

  @Transactional
  public long create(StaffPrincipal principal, ObjectNode body) {
    Long categoryId = importRepository.findDefaultCategoryId(principal.officeId());
    if (categoryId == null) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    body.put("categoryId", categoryId);
    return commandService.createImported(principal, body).propertyId();
  }

  @Transactional(readOnly = true)
  public List<MatchCandidate> findMatchCandidates(long officeId, Collection<String> addresses) {
    return importRepository.findMatchCandidates(officeId, addresses).stream()
        .map(
            row ->
                new MatchCandidate(
                    row.propertyId(), row.normalizedAddress(), row.normalizedAddressDetail()))
        .toList();
  }

  @Transactional(readOnly = true)
  public ImportedRecordState inspect(long officeId, long propertyId) {
    return importRepository.inspect(officeId, propertyId);
  }

  @Transactional
  public void remove(long propertyId) {
    archiveRepository.deleteDraftRows(propertyId);
  }
}
