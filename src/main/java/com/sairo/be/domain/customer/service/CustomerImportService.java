package com.sairo.be.domain.customer.service;

import com.sairo.be.domain.customer.repository.CustomerImportRepository;
import com.sairo.be.domain.customer.repository.CustomerReadRepository;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.ImportedRecordState;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class CustomerImportService {

  public record MatchCandidate(
      long customerId, String normalizedName, String phoneDigits, String secondaryPhoneDigits) {}

  private final CustomerCommandService commandService;
  private final CustomerImportRepository importRepository;
  private final CustomerReadRepository readRepository;

  public CustomerImportService(
      CustomerCommandService commandService,
      CustomerImportRepository importRepository,
      CustomerReadRepository readRepository) {
    this.commandService = commandService;
    this.importRepository = importRepository;
    this.readRepository = readRepository;
  }

  @Transactional
  public long create(StaffPrincipal principal, JsonNode body) {
    return commandService.createImported(principal, body).customerId();
  }

  @Transactional(readOnly = true)
  public List<MatchCandidate> findMatchCandidates(long officeId, Collection<String> names) {
    return importRepository.findMatchCandidates(officeId, names).stream()
        .map(
            row ->
                new MatchCandidate(
                    row.customerId(),
                    row.normalizedName(),
                    row.phoneDigits(),
                    row.secondaryPhoneDigits()))
        .toList();
  }

  @Transactional(readOnly = true)
  public ImportedRecordState inspect(long officeId, long customerId) {
    return importRepository.inspect(officeId, customerId);
  }

  @Transactional
  public void remove(long customerId) {
    readRepository.deleteCustomerRows(customerId);
  }
}
