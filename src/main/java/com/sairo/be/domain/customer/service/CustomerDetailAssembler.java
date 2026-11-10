package com.sairo.be.domain.customer.service;

import com.sairo.be.domain.customer.dto.response.CustomerDetailResponse;
import com.sairo.be.domain.customer.dto.response.DemandConditionResponse;
import com.sairo.be.domain.customer.entity.Customer;
import com.sairo.be.domain.customer.repository.CustomerReadRepository;
import com.sairo.be.global.support.TimeRules;
import com.sairo.be.global.support.WithdrawnMemberDisplay;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class CustomerDetailAssembler {

  private static final Set<String> OPEN_CONTRACT_STATUSES = Set.of("DRAFT", "SIGNED");

  private final CustomerReadRepository readRepository;
  private final Clock clock;

  public CustomerDetailAssembler(CustomerReadRepository readRepository, Clock clock) {
    this.readRepository = readRepository;
    this.clock = clock;
  }

  public CustomerDetailResponse assemble(Customer customer) {
    long id = customer.getId();
    boolean merged = readRepository.isMerged(id);
    Long mergedInto = merged ? readRepository.finalCustomerId(id) : null;
    List<Long> ids = merged ? List.of() : readRepository.mergedSet(id);
    LocalDate today = TimeRules.displayDate(clock.instant());
    List<String> roles = merged ? List.of() : roles(customer);
    boolean deletable = !merged && readRepository.isDeletable(ids);
    List<DemandConditionResponse> conditions = merged ? List.of() : conditions(customer, ids);
    List<CustomerReadRepository.RelatedProperty> related =
        merged ? List.of() : readRepository.findRelatedProperties(ids);
    List<CustomerDetailResponse.RelatedProperty> relatedItems =
        related.stream().map(CustomerDetailAssembler::relatedItem).toList();
    List<CustomerDetailResponse.RelatedProperty> current =
        related.stream()
            .filter(row -> !"INTEREST".equals(row.role()) && row.confirmed())
            .filter(row -> !row.startDate().isAfter(today))
            .filter(row -> row.endDate() == null || !row.endDate().isBefore(today))
            .map(CustomerDetailAssembler::relatedItem)
            .toList();
    List<CustomerDetailResponse.ContractItem> upcoming = new ArrayList<>();
    List<CustomerDetailResponse.ContractItem> past = new ArrayList<>();
    if (!merged) {
      for (CustomerReadRepository.ContractRow row :
          readRepository.findContracts(customer.getOfficeId(), ids)) {
        CustomerDetailResponse.ContractItem item = contractItem(row);
        boolean open =
            OPEN_CONTRACT_STATUSES.contains(row.contractStatus())
                && (row.leaseEndDate() == null || !row.leaseEndDate().isBefore(today));
        (open ? upcoming : past).add(item);
      }
    }
    List<CustomerDetailResponse.ConsultationItem> consultations =
        merged
            ? List.of()
            : readRepository.findConsultations(ids).stream()
                .map(
                    row ->
                        new CustomerDetailResponse.ConsultationItem(
                            row.consultationId(),
                            row.consultationType(),
                            TimeRules.toDisplayOffset(row.occurredAt()),
                            row.content(),
                            row.authorMembershipId(),
                            WithdrawnMemberDisplay.name(
                                row.authorWithdrawn(), row.authorName(), row.authorAliasNo()),
                            row.revisionCount()))
                .toList();
    List<CustomerDetailResponse.VisitItem> visits =
        merged
            ? List.of()
            : readRepository.findVisits(customer.getOfficeId(), ids).stream()
                .map(
                    row ->
                        new CustomerDetailResponse.VisitItem(
                            row.coordinationId(),
                            row.status(),
                            row.periodStartDate(),
                            row.periodEndDate(),
                            row.candidatePropertyCount()))
                .toList();
    List<CustomerDetailResponse.TaskItem> tasks =
        merged
            ? List.of()
            : readRepository.findTasks(customer.getOfficeId(), ids).stream()
                .map(
                    row ->
                        new CustomerDetailResponse.TaskItem(
                            row.taskId(), row.title(), row.dueDate(), row.status()))
                .toList();
    String status = merged ? "MERGED" : customer.isArchived() ? "ARCHIVED" : "ACTIVE";
    return new CustomerDetailResponse(
        id,
        customer.getName(),
        customer.isCorporation(),
        customer.getManagingMembershipId(),
        customer.getPhone(),
        customer.getSecondaryPhone(),
        customer.getEmail(),
        customer.getMemo(),
        customer.getDataSource(),
        customer.getProcessingBasis().name(),
        customer.getDataPurpose(),
        customer.getRetentionUntil(),
        TimeRules.toDisplayOffset(customer.getDeletedAt()),
        TimeRules.toDisplayOffset(customer.getCreatedAt()),
        TimeRules.toDisplayOffset(customer.getUpdatedAt()),
        status,
        mergedInto,
        roles,
        deletable,
        conditions,
        relatedItems,
        current,
        upcoming,
        past,
        consultations,
        visits,
        tasks,
        customer.getOfficeId(),
        customer.getVersion());
  }

  public List<String> roles(Customer customer) {
    CustomerReadRepository.RoleFlags flags =
        readRepository
            .findRoles(customer.getOfficeId(), List.of(customer.getId()))
            .get(customer.getId());
    return rolesOf(flags);
  }

  public static List<String> rolesOf(CustomerReadRepository.RoleFlags flags) {
    List<String> roles = new ArrayList<>();
    if (flags != null) {
      if (flags.demand()) {
        roles.add("DEMAND");
      }
      if (flags.owner()) {
        roles.add("OWNER");
      }
      if (flags.occupant()) {
        roles.add("OCCUPANT");
      }
    }
    if (roles.isEmpty()) {
      roles.add("OTHER");
    }
    return roles;
  }

  public List<DemandConditionResponse> conditions(Customer customer, List<Long> ids) {
    List<CustomerReadRepository.ConditionRow> rows = readRepository.findConditions(ids);
    List<CustomerReadRepository.RegionRow> regions =
        readRepository.findRegions(
            rows.stream().map(CustomerReadRepository.ConditionRow::conditionId).toList());
    return rows.stream().map(row -> conditionResponse(customer, row, regions)).toList();
  }

  public DemandConditionResponse conditionResponse(
      Customer customer,
      CustomerReadRepository.ConditionRow row,
      List<CustomerReadRepository.RegionRow> regions) {
    return new DemandConditionResponse(
        row.conditionId(),
        row.customerId(),
        row.dealType(),
        row.categoryId(),
        row.priceMin(),
        row.priceMax(),
        row.depositMin(),
        row.depositMax(),
        row.rentMin(),
        row.rentMax(),
        row.areaMin(),
        row.areaMax(),
        row.moveInDate(),
        regions.stream()
            .filter(region -> region.conditionId() == row.conditionId())
            .map(
                region ->
                    new DemandConditionResponse.Region(
                        region.regionId(), region.conditionId(), region.regionName()))
            .toList(),
        TimeRules.toDisplayOffset(row.createdAt()),
        customer.getOfficeId(),
        TimeRules.toDisplayOffset(customer.getUpdatedAt()),
        customer.getVersion());
  }

  private static CustomerDetailResponse.RelatedProperty relatedItem(
      CustomerReadRepository.RelatedProperty row) {
    return new CustomerDetailResponse.RelatedProperty(
        row.propertyId(),
        row.propertyName(),
        row.role(),
        row.startDate(),
        row.endDate(),
        row.linkId(),
        row.interestId(),
        row.note());
  }

  private static CustomerDetailResponse.ContractItem contractItem(
      CustomerReadRepository.ContractRow row) {
    return new CustomerDetailResponse.ContractItem(
        row.contractId(),
        row.propertyId(),
        row.propertyName(),
        row.dealType(),
        row.contractStatus(),
        row.signedDate(),
        row.leaseStartDate(),
        row.leaseEndDate());
  }
}
